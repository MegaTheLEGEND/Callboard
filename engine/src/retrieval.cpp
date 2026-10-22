#include "retrieval.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>

#if defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>
#define RVC_NEON 1
#elif defined(__AVX2__) && defined(__F16C__) && defined(__FMA__)
#include <immintrin.h>
#define RVC_AVX2 1
#endif

namespace rvc {

float RetrievalIndex::half_to_float(uint16_t h) {
  const uint32_t sign = uint32_t(h & 0x8000u) << 16;
  uint32_t exp = (h >> 10) & 0x1Fu, man = h & 0x3FFu, bits;
  if (exp == 0) {
    if (man == 0) {
      bits = sign;
    } else {  // subnormal
      exp = 127 - 15 + 1;
      while ((man & 0x400u) == 0) { man <<= 1; --exp; }
      man &= 0x3FFu;
      bits = sign | (exp << 23) | (man << 13);
    }
  } else if (exp == 31) {
    bits = sign | 0x7F800000u | (man << 13);
  } else {
    bits = sign | ((exp - 15 + 127) << 23) | (man << 13);
  }
  float f;
  std::memcpy(&f, &bits, 4);
  return f;
}

RetrievalIndex::~RetrievalIndex() {
  if (map_) munmap(map_, map_len_);
}

bool RetrievalIndex::load(const std::string& path, std::string* err) {
  auto fail = [&](const char* m) { if (err) *err = m; return false; };
  int fd = open(path.c_str(), O_RDONLY);
  if (fd < 0) return fail("cannot open index file");
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size < 16) { close(fd); return fail("bad index file"); }
  void* m = mmap(nullptr, size_t(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (m == MAP_FAILED) return fail("mmap failed");
  const uint8_t* p = static_cast<const uint8_t*>(m);
  uint32_t n, d, ver;
  if (std::memcmp(p, "RVCI", 4) != 0) { munmap(m, size_t(st.st_size)); return fail("bad magic"); }
  std::memcpy(&n, p + 4, 4); std::memcpy(&d, p + 8, 4); std::memcpy(&ver, p + 12, 4);
  const size_t need = 16 + size_t(n) * 4 + size_t(n) * d * 2;
  if (ver != 1 || (d % 8) != 0 || need > size_t(st.st_size)) {
    munmap(m, size_t(st.st_size));
    return fail("unsupported index (dim must be a multiple of 8)");
  }
  if (map_) munmap(map_, map_len_);
  map_ = m; map_len_ = size_t(st.st_size);
  n_ = int(n); d_ = int(d);
  norms_ = reinterpret_cast<const float*>(p + 16);
  vecs_ = reinterpret_cast<const uint16_t*>(p + 16 + size_t(n) * 4);
  madvise(map_, map_len_, MADV_WILLNEED);
  return true;
}

void RetrievalIndex::TopK::reset() {
  for (int i = 0; i < K; ++i) { s[i] = INFINITY; ix[i] = -1; }
  worst = 0;
}
void RetrievalIndex::TopK::push(float score, int idx) {
  if (score >= s[worst]) return;
  s[worst] = score; ix[worst] = idx;
  worst = 0;
  for (int i = 1; i < K; ++i) if (s[i] > s[worst]) worst = i;
}

// dots[g*4 + j] = <vec, q[(g*4+j)*d ...]> for a 4-query group
#if RVC_NEON
static inline void dot4(const uint16_t* v, const float* q, int d, float* out) {
  float32x4_t a0 = vdupq_n_f32(0), a1 = a0, a2 = a0, a3 = a0;
  const float *q0 = q, *q1 = q + d, *q2 = q + 2 * d, *q3 = q + 3 * d;
  for (int j = 0; j < d; j += 4) {
    const float32x4_t c = vcvt_f32_f16(vreinterpret_f16_u16(vld1_u16(v + j)));
    a0 = vfmaq_f32(a0, c, vld1q_f32(q0 + j));
    a1 = vfmaq_f32(a1, c, vld1q_f32(q1 + j));
    a2 = vfmaq_f32(a2, c, vld1q_f32(q2 + j));
    a3 = vfmaq_f32(a3, c, vld1q_f32(q3 + j));
  }
  out[0] = vaddvq_f32(a0); out[1] = vaddvq_f32(a1);
  out[2] = vaddvq_f32(a2); out[3] = vaddvq_f32(a3);
}
#elif RVC_AVX2
static inline float hsum(__m256 v) {
  __m128 lo = _mm256_castps256_ps128(v), hi = _mm256_extractf128_ps(v, 1);
  lo = _mm_add_ps(lo, hi);
  lo = _mm_hadd_ps(lo, lo);
  lo = _mm_hadd_ps(lo, lo);
  return _mm_cvtss_f32(lo);
}
static inline void dot4(const uint16_t* v, const float* q, int d, float* out) {
  __m256 a0 = _mm256_setzero_ps(), a1 = a0, a2 = a0, a3 = a0;
  const float *q0 = q, *q1 = q + d, *q2 = q + 2 * d, *q3 = q + 3 * d;
  for (int j = 0; j < d; j += 8) {
    const __m256 c = _mm256_cvtph_ps(_mm_loadu_si128(reinterpret_cast<const __m128i*>(v + j)));
    a0 = _mm256_fmadd_ps(c, _mm256_loadu_ps(q0 + j), a0);
    a1 = _mm256_fmadd_ps(c, _mm256_loadu_ps(q1 + j), a1);
    a2 = _mm256_fmadd_ps(c, _mm256_loadu_ps(q2 + j), a2);
    a3 = _mm256_fmadd_ps(c, _mm256_loadu_ps(q3 + j), a3);
  }
  out[0] = hsum(a0); out[1] = hsum(a1); out[2] = hsum(a2); out[3] = hsum(a3);
}
#else
static inline void dot4(const uint16_t* v, const float* q, int d, float* out) {
  float a[4] = {0, 0, 0, 0};
  for (int j = 0; j < d; ++j) {
    const float c = RetrievalIndex::half_to_float(v[j]);
    for (int g = 0; g < 4; ++g) a[g] += c * q[g * d + j];
  }
  for (int g = 0; g < 4; ++g) out[g] = a[g];
}
#endif

void RetrievalIndex::search_range(const float* q, const float* qn, int Tpad, int T, int lo,
                                  int hi, TopK* out) const {
  for (int t = 0; t < T; ++t) out[t].reset();
  float dots[16];
  const int step = stride_.load(std::memory_order_relaxed);
  for (int i = lo; i < hi; i += step) {
    const uint16_t* v = vecs_ + size_t(i) * d_;
    for (int g = 0; g < Tpad; g += 4) dot4(v, q + size_t(g) * d_, d_, dots + g);
    const float vn = norms_[i];
    for (int t = 0; t < T; ++t) {
      const float d2 = qn[t] + vn - 2.f * dots[t];
      out[t].push(d2, i);
    }
  }
}

void RetrievalIndex::blend(float* feats, int T, float rate, int threads) const {
  if (!loaded() || T <= 0 || T > 16 || rate <= 0.f) return;
  const int d = d_;
  const int Tpad = (T + 3) & ~3;
  std::vector<float> q(size_t(Tpad) * d, 0.f);
  std::memcpy(q.data(), feats, sizeof(float) * size_t(T) * d);
  float qn[16] = {0};
  for (int t = 0; t < T; ++t) {
    double s = 0;
    for (int j = 0; j < d; ++j) s += double(q[size_t(t) * d + j]) * q[size_t(t) * d + j];
    qn[t] = float(s);
  }

  threads = std::max(1, std::min(threads, 8));
  std::vector<std::vector<TopK>> parts(threads, std::vector<TopK>(T));
  std::vector<std::thread> pool;
  for (int w = 0; w < threads; ++w) {
    const int lo = int(int64_t(n_) * w / threads), hi = int(int64_t(n_) * (w + 1) / threads);
    auto fn = [&, w, lo, hi] { search_range(q.data(), qn, Tpad, T, lo, hi, parts[w].data()); };
    if (w + 1 == threads) fn(); else pool.emplace_back(fn);
  }
  for (auto& th : pool) th.join();

  std::vector<float> mixed(d);
  for (int t = 0; t < T; ++t) {
    TopK best; best.reset();
    for (int w = 0; w < threads; ++w)
      for (int k = 0; k < K; ++k) if (parts[w][t].ix[k] >= 0) best.push(parts[w][t].s[k], parts[w][t].ix[k]);
    double wsum = 0, wts[K];
    int cnt = 0;
    for (int k = 0; k < K; ++k) {
      if (best.ix[k] < 0) { wts[k] = 0; continue; }
      const double sc = std::max(double(best.s[k]), 1e-9);
      wts[k] = 1.0 / (sc * sc);  // np.square(1 / score)
      wsum += wts[k];
      ++cnt;
    }
    if (cnt == 0 || wsum <= 0) continue;
    std::fill(mixed.begin(), mixed.end(), 0.f);
    for (int k = 0; k < K; ++k) {
      if (best.ix[k] < 0) continue;
      const float w = float(wts[k] / wsum);
      const uint16_t* v = vecs_ + size_t(best.ix[k]) * d;
      for (int j = 0; j < d; ++j) mixed[j] += w * half_to_float(v[j]);
    }
    float* f = feats + size_t(t) * d;
    for (int j = 0; j < d; ++j) f[j] = rate * mixed[j] + (1.f - rate) * f[j];
  }
}

}  // namespace rvc
