#include "dsp.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace rvc {

// ------------------------------------------------------------------ kernels
static double bessel_i0(double x) {
  double sum = 1.0, term = 1.0;
  const double q = x * x / 4.0;
  for (int k = 1; k < 64; ++k) {
    term *= q / (double(k) * double(k));
    sum += term;
    if (term < 1e-14 * sum) break;
  }
  return sum;
}

ResamplerKernel ResamplerKernel::design(int L, int M, int half_len_in) {
  ResamplerKernel k;
  k.L = L;
  k.M = M;
  const int mx = std::max(L, M);
  k.center = half_len_in * mx;
  k.N = 2 * k.center + 1;
  k.h.resize(k.N);
  const double fc = 0.46 / mx;  // 92 % of the lower Nyquist, normalised to the upsampled rate
  const double beta = 8.0;
  const double i0b = bessel_i0(beta);
  const double kPi = 3.14159265358979323846;
  for (int j = 0; j < k.N; ++j) {
    const double t = j - k.center;
    const double x = 2.0 * fc * t;
    const double sinc = (t == 0.0) ? 1.0 : std::sin(kPi * x) / (kPi * x);
    const double r = t / double(k.center);
    const double w = bessel_i0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
    k.h[j] = float(double(L) * 2.0 * fc * sinc * w);
  }
  return k;
}

StreamResampler::StreamResampler(const ResamplerKernel& k) : k_(k) {
  K_ = (k_.N + k_.L - 1) / k_.L;
  reset();
}

void StreamResampler::reset() {
  hist_.assign(K_ - 1, 0.f);
  pos_ = 0;
}

size_t StreamResampler::process(const float* in, size_t n_in, float* out) {
  const int L = k_.L, M = k_.M, N = k_.N;
  ext_.resize(size_t(K_ - 1) + n_in);
  std::memcpy(ext_.data(), hist_.data(), sizeof(float) * (K_ - 1));
  std::memcpy(ext_.data() + (K_ - 1), in, sizeof(float) * n_in);

  size_t n_out = 0;
  long p = pos_;
  const long lim = long(n_in) * L;
  while (p < lim) {
    const long i0 = p / L;
    const int ph = int(p % L);
    const float* x = ext_.data() + (K_ - 1) + i0;  // x[-k] = ext[(K-1)+i0-k]
    float acc = 0.f;
    for (int k = 0, idx = ph; idx < N; ++k, idx += L) acc += k_.h[idx] * x[-k];
    out[n_out++] = acc;
    p += M;
  }
  pos_ = p - lim;
  std::memcpy(hist_.data(), ext_.data() + n_in, sizeof(float) * (K_ - 1));
  return n_out;
}

void resample_block(const ResamplerKernel& k, const float* in, size_t n_in, float* out,
                    size_t n_out) {
  const long L = k.L, M = k.M, c = k.center;
  for (size_t m = 0; m < n_out; ++m) {
    const long t = long(m) * M;  // upsampled position of this output sample
    // contributing inputs satisfy |t - i*L| <= c
    long i_lo = (t - c <= 0) ? 0 : (t - c + L - 1) / L;  // ceil((t-c)/L), clamped at 0
    long i_hi = (t + c) / L;                              // floor((t+c)/L)
    if (i_hi > long(n_in) - 1) i_hi = long(n_in) - 1;
    float acc = 0.f;
    for (long i = i_lo; i <= i_hi; ++i) acc += in[i] * k.h[size_t(t - i * L + c)];
    out[m] = acc;
  }
}

// ----------------------------------------------------------------- NSF source
void sine_excitation(const float* f0, int L, int sr, int upp, float lin_w, float lin_b,
                     const float* noise, float* out) {
  const float kSineAmp = 0.1f, kNoiseStd = 0.003f;
  const float two_pi = float(2.0 * 3.14159265358979323846);
  const float fsr = float(sr);
  float acc = 0.f;      // wrapped running phase (np.fmod(cumsum, 1))
  float cumsum = 0.f;   // raw float32 cumsum of rad2 (matches np.cumsum in float32)
  float shifted = 0.f;  // phase offset of the current frame (acc of previous frame)
  for (int t = 0; t < L; ++t) {
    const float base = f0[t] / fsr;
    const float last = base * float(upp);
    const float rad2 = std::fmod(last + 0.5f, 1.0f) - 0.5f;
    const bool voiced = f0[t] > 0.f;
    const float noise_amp = voiced ? kNoiseStd : kSineAmp / 3.f;
    for (int k = 0; k < upp; ++k) {
      const float rad = base * float(k + 1) + shifted;
      float s = std::sin(two_pi * rad) * kSineAmp;
      const float nz = noise ? noise[size_t(t) * upp + k] : 0.f;
      s = (voiced ? s : 0.f) + noise_amp * nz;
      out[size_t(t) * upp + k] = std::tanh(lin_w * s + lin_b);
    }
    cumsum += rad2;
    acc = std::fmod(cumsum, 1.0f);
    shifted = acc;
  }
}

// --------------------------------------------------------------------- pitch
void fcpe_decode(const float* latent, int frames, int bins, const float* cents,
                 float threshold, int half_window, float* f0_out) {
  for (int t = 0; t < frames; ++t) {
    const float* y = latent + size_t(t) * bins;
    int im = 0;
    float mx = y[0];
    for (int i = 1; i < bins; ++i)
      if (y[i] > mx) { mx = y[i]; im = i; }
    if (mx <= threshold) { f0_out[t] = 0.f; continue; }
    float num = 0.f, den = 0.f;
    for (int o = -half_window; o <= half_window; ++o) {
      const int i = std::min(std::max(im + o, 0), bins - 1);  // torchfcpe clamps
      num += cents[i] * y[i];
      den += y[i];
    }
    f0_out[t] = 10.f * std::exp2((num / den) / 1200.f);
  }
}

void interp_unvoiced(float* f0, int n) {
  int first = -1, last = -1;
  for (int i = 0; i < n; ++i)
    if (f0[i] != 0.f) { if (first < 0) first = i; last = i; }
  if (first < 0) return;
  int prev = -1;
  for (int i = 0; i < n; ++i) {
    if (f0[i] != 0.f) { prev = i; continue; }
    if (i < first) { f0[i] = f0[first]; continue; }
    if (i > last) { f0[i] = f0[last]; continue; }
    int nxt = i;
    while (f0[nxt] == 0.f) ++nxt;  // safe: i < last
    const float a = f0[prev], b = f0[nxt];
    f0[i] = a + (b - a) * float(i - prev) / float(nxt - prev);
  }
}

void f0_to_coarse(const float* f0, int n, int64_t* coarse) {
  static const float mel_min = float(1127.0 * std::log(1.0 + 50.0 / 700.0));
  static const float mel_max = float(1127.0 * std::log(1.0 + 1100.0 / 700.0));
  for (int i = 0; i < n; ++i) {
    float m = 1127.f * std::log(1.f + f0[i] / 700.f);
    if (m > 0.f) m = (m - mel_min) * 254.f / (mel_max - mel_min) + 1.f;
    if (m <= 1.f) m = 1.f;
    if (m > 255.f) m = 255.f;
    coarse[i] = int64_t(std::nearbyint(m));  // torch.round: half to even
  }
}

PitchCache::PitchCache(int capacity) : coarse_(capacity, 0), f0_(capacity, 0.f) {}
void PitchCache::clear() {
  std::fill(coarse_.begin(), coarse_.end(), 0);
  std::fill(f0_.begin(), f0_.end(), 0.f);
}
void PitchCache::update(const int64_t* coarse, const float* f0, int n, int shift) {
  const int cap = int(coarse_.size());
  std::memmove(coarse_.data(), coarse_.data() + shift, sizeof(int64_t) * (cap - shift));
  std::memmove(f0_.data(), f0_.data() + shift, sizeof(float) * (cap - shift));
  const int cnt = n - 4;  // pitch[3 : n-1]
  std::memcpy(coarse_.data() + cap - cnt, coarse + 3, sizeof(int64_t) * cnt);
  std::memcpy(f0_.data() + cap - cnt, f0 + 3, sizeof(float) * cnt);
}
void PitchCache::tail(int n, int64_t* coarse_out, float* f0_out) const {
  const int cap = int(coarse_.size());
  std::memcpy(coarse_out, coarse_.data() + cap - n, sizeof(int64_t) * n);
  std::memcpy(f0_out, f0_.data() + cap - n, sizeof(float) * n);
}

// ---------------------------------------------------------------------- SOLA
Sola::Sola(int block, int sola_buffer, int sola_search)
    : block_(block), buf_(sola_buffer), search_(sola_search) {
  fade_in_.resize(buf_);
  fade_out_.resize(buf_);
  for (int i = 0; i < buf_; ++i) {
    const float x = buf_ > 1 ? float(i) / float(buf_ - 1) : 1.f;
    const float s = std::sin(0.5f * 3.14159265358979323846f * x);
    fade_in_[i] = s * s;
    fade_out_[i] = 1.f - fade_in_[i];
  }
  reset();
}
void Sola::reset() { sola_buffer_.assign(buf_, 0.f); }

int Sola::process(float* wav, float* out) {
  int best = 0;
  float best_v = -1e30f;
  for (int k = 0; k <= search_; ++k) {
    float nom = 0.f, den = 0.f;
    const float* c = wav + k;
    for (int j = 0; j < buf_; ++j) {
      nom += c[j] * sola_buffer_[j];
      den += c[j] * c[j];
    }
    const float v = nom / std::sqrt(den + 1e-8f);
    if (v > best_v) { best_v = v; best = k; }
  }
  float* w = wav + best;
  for (int i = 0; i < buf_; ++i) w[i] = w[i] * fade_in_[i] + sola_buffer_[i] * fade_out_[i];
  std::memcpy(sola_buffer_.data(), w + block_, sizeof(float) * buf_);
  std::memcpy(out, w, sizeof(float) * block_);
  return best;
}

// ----------------------------------------------------------------------- RNG
Rng::Rng(uint64_t seed) {
  uint64_t z = seed;
  for (int i = 0; i < 4; ++i) {  // splitmix64
    z += 0x9E3779B97F4A7C15ull;
    uint64_t x = z;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ull;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBull;
    s_[i] = uint32_t((x ^ (x >> 31)) >> 16) | 1u;
  }
}
uint32_t Rng::next() {  // xoshiro128**
  auto rotl = [](uint32_t x, int k) { return (x << k) | (x >> (32 - k)); };
  const uint32_t result = rotl(s_[1] * 5, 7) * 9;
  const uint32_t t = s_[1] << 9;
  s_[2] ^= s_[0]; s_[3] ^= s_[1]; s_[1] ^= s_[2]; s_[0] ^= s_[3];
  s_[2] ^= t;
  s_[3] = rotl(s_[3], 11);
  return result;
}
float Rng::uniform() { return (float(next() >> 8) + 0.5f) * (1.0f / 16777216.0f); }  // (0,1)
void Rng::fill_normal(float* out, size_t n) {
  for (size_t i = 0; i < n; ++i) {
    if (have_spare_) { out[i] = spare_; have_spare_ = false; continue; }
    const float u1 = uniform(), u2 = uniform();
    const float r = std::sqrt(-2.f * std::log(u1));
    const float a = 6.28318530718f * u2;
    out[i] = r * std::cos(a);
    spare_ = r * std::sin(a);
    have_spare_ = true;
  }
}

// ----------------------------------------------------------------- noise gate
void noise_gate(float* x, size_t n, int zc, float db) {
  const size_t frames = n / size_t(zc);
  std::vector<char> kill(frames, 0);
  const float thr = std::pow(10.f, db / 20.f);
  for (size_t f = 0; f < frames; ++f) {
    long a = long(f) * zc - (3 * zc) / 2, b = long(f) * zc + (5 * zc) / 2;  // 4-frame window
    a = std::max(a, 0L);
    b = std::min(b, long(n));
    double s = 0;
    for (long i = a; i < b; ++i) s += double(x[i]) * x[i];
    const float rms = float(std::sqrt(s / double(std::max(1L, b - a))));
    kill[f] = rms < thr;
  }
  for (size_t f = 0; f < frames; ++f)
    if (kill[f]) std::memset(x + f * zc, 0, sizeof(float) * zc);
}

}  // namespace rvc
