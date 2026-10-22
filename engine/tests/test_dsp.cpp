// Host-side tests for engine/src/{dsp,retrieval}.cpp.
//   python export/gen_test_vectors.py /tmp/rvc_vectors
//   ./test_dsp /tmp/rvc_vectors
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "../src/dsp.h"
#include "../src/retrieval.h"

using namespace rvc;

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, ...)                                  \
  do {                                                    \
    if (cond) { ++g_pass; }                               \
    else { ++g_fail; std::printf("  FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } \
  } while (0)

static std::string g_dir;

template <class T>
static std::vector<T> load(const std::string& name) {
  FILE* f = std::fopen((g_dir + "/" + name).c_str(), "rb");
  if (!f) { std::printf("missing vector %s\n", name.c_str()); std::exit(2); }
  std::fseek(f, 0, SEEK_END);
  const long n = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  std::vector<T> v(size_t(n) / sizeof(T));
  if (std::fread(v.data(), 1, size_t(n), f) != size_t(n)) std::exit(2);
  std::fclose(f);
  return v;
}

static double max_abs_diff(const float* a, const float* b, size_t n) {
  double m = 0;
  for (size_t i = 0; i < n; ++i) m = std::max(m, std::fabs(double(a[i]) - b[i]));
  return m;
}

// ----------------------------------------------------------------- resamplers
static void test_resamplers() {
  std::printf("[resamplers]\n");
  const double kPi = 3.14159265358979323846;
  // streaming 48k -> 16k: 1 kHz + 3 kHz tones, compare with the ideal 16k signal
  {
    auto k = ResamplerKernel::design(1, 3);
    StreamResampler rs(k);
    const int n = 48000;
    std::vector<float> in(n), out(n), ideal(n / 3);
    for (int i = 0; i < n; ++i)
      in[i] = float(0.4 * std::sin(2 * kPi * 1000 * i / 48000.0) + 0.3 * std::sin(2 * kPi * 3000 * i / 48000.0));
    size_t got = 0;
    for (int off = 0; off < n; off += 4800) got += rs.process(in.data() + off, 4800, out.data() + got);
    CHECK(got == size_t(n / 3), "stream out count %zu", got);
    // group delay = center/L input samples = 48 samples at 48k = 16 at 16k
    const double delay = double(k.center) / k.L / 3.0;
    double worst = 0;
    for (size_t i = 400; i < got - 400; ++i) {
      const double t = (double(i) - delay) / 16000.0;
      const double e = 0.4 * std::sin(2 * kPi * 1000 * t) + 0.3 * std::sin(2 * kPi * 3000 * t);
      worst = std::max(worst, std::fabs(e - out[i]));
    }
    std::printf("  48k->16k stream: max err vs ideal %.2e (delay %.1f samples@16k)\n", worst, delay);
    CHECK(worst < 2e-3, "48k->16k error %.2e", worst);

    // chunk invariance: tiny irregular chunks must give identical output
    StreamResampler rs2(k);
    std::vector<float> out2(n);
    size_t g2 = 0, p = 0;
    const int chunks[] = {1, 7, 480, 95, 3, 1024};
    for (int c = 0; p < size_t(n); c = (c + 1) % 6) {
      const size_t m = std::min<size_t>(chunks[c], n - p);
      g2 += rs2.process(in.data() + p, m, out2.data() + g2);
      p += m;
    }
    CHECK(g2 == got, "chunked count %zu vs %zu", g2, got);
    CHECK(max_abs_diff(out.data(), out2.data(), std::min(got, g2)) < 1e-6, "chunk invariance");
  }
  // block 40k -> 48k (zero-phase, no delay)
  {
    auto k = ResamplerKernel::design(6, 5);
    const int n = 6000, m = 7200;
    std::vector<float> in(n), out(m);
    for (int i = 0; i < n; ++i) in[i] = float(0.5 * std::sin(2 * kPi * 1500 * i / 40000.0));
    resample_block(k, in.data(), n, out.data(), m);
    double worst = 0;
    for (int i = 100; i < m - 100; ++i)
      worst = std::max(worst, std::fabs(0.5 * std::sin(2 * kPi * 1500 * i / 48000.0) - out[i]));
    std::printf("  40k->48k block : max err vs ideal %.2e\n", worst);
    CHECK(worst < 2e-3, "40k->48k error %.2e", worst);
  }
}

// ------------------------------------------------------------------ sine source
static void test_sine() {
  std::printf("[sine excitation]\n");
  auto f0 = load<float>("sine_f0.f32");
  auto noise = load<float>("sine_noise.f32");
  auto e0 = load<float>("sine_expect.f32");
  auto e1 = load<float>("sine_expect_noise.f32");
  const int L = 15, sr = 40000, upp = 400;
  std::vector<float> out(size_t(L) * upp);
  sine_excitation(f0.data(), L, sr, upp, 0.8337f, -0.0123f, nullptr, out.data());
  double d0 = max_abs_diff(out.data(), e0.data(), out.size());
  sine_excitation(f0.data(), L, sr, upp, 0.8337f, -0.0123f, noise.data(), out.data());
  double d1 = max_abs_diff(out.data(), e1.data(), out.size());
  std::printf("  max err: no-noise %.2e, with noise %.2e\n", d0, d1);
  CHECK(d0 < 5e-4 && d1 < 5e-4, "sine mismatch");
}

// ----------------------------------------------------------------------- pitch
static void test_pitch() {
  std::printf("[pitch utils]\n");
  {  // fcpe decode
    auto lat = load<float>("fcpe_latent.f32");
    auto cents = load<float>("fcpe_cents.f32");
    auto exp = load<float>("fcpe_expect.f32");
    std::vector<float> out(16);
    fcpe_decode(lat.data(), 16, 360, cents.data(), 0.006f, 4, out.data());
    double worst = 0;
    int unv = 0;
    for (int i = 0; i < 16; ++i) {
      if (exp[i] == 0) { ++unv; CHECK(out[i] == 0.f, "frame %d should be unvoiced", i); }
      else worst = std::max(worst, std::fabs(double(out[i]) - exp[i]) / exp[i]);
    }
    std::printf("  fcpe_decode: worst rel err %.2e (%d unvoiced frames)\n", worst, unv);
    CHECK(worst < 1e-5 && unv >= 2, "fcpe decode");
  }
  {  // interp
    auto in = load<float>("interp_in.f32");
    auto exp = load<float>("interp_expect.f32");
    interp_unvoiced(in.data(), 16);
    CHECK(max_abs_diff(in.data(), exp.data(), 16) < 1e-3, "interp_unvoiced");
    std::vector<float> z(16, 0.f);
    interp_unvoiced(z.data(), 16);
    CHECK(*std::max_element(z.begin(), z.end()) == 0.f, "all-unvoiced stays zero");
  }
  {  // coarse
    auto in = load<float>("coarse_in.f32");
    auto exp = load<int64_t>("coarse_expect.i64");
    std::vector<int64_t> out(16);
    f0_to_coarse(in.data(), 16, out.data());
    int bad = 0;
    for (int i = 0; i < 16; ++i) if (out[i] != exp[i]) { ++bad; std::printf("  coarse[%d]=%lld expect %lld\n", i, (long long)out[i], (long long)exp[i]); }
    CHECK(bad == 0, "coarse mismatches: %d", bad);
  }
  {  // cache
    PitchCache c(1024);
    for (int s = 0; s < 4; ++s) {
      auto pc = load<int64_t>("pcache_c" + std::to_string(s) + ".i64");
      auto pf = load<float>("pcache_f" + std::to_string(s) + ".f32");
      c.update(pc.data(), pf.data(), 16, 10);
    }
    std::vector<int64_t> tc(66); std::vector<float> tf(66);
    c.tail(66, tc.data(), tf.data());
    auto ec = load<int64_t>("pcache_tail_c.i64");
    auto ef = load<float>("pcache_tail_f.f32");
    CHECK(std::memcmp(tc.data(), ec.data(), 66 * 8) == 0, "cache coarse tail");
    CHECK(std::memcmp(tf.data(), ef.data(), 66 * 4) == 0, "cache f0 tail");
  }
}

// ------------------------------------------------------------------------ SOLA
static void test_sola() {
  std::printf("[SOLA]\n");
  const int zc = 480, block = 10 * zc, buf = 4 * zc, search = zc, n_in = block + buf + search;
  auto in = load<float>("sola_in.f32");
  auto exp = load<float>("sola_out.f32");
  Sola s(block, buf, search);
  std::vector<float> out(block);
  double worst = 0;
  for (int st = 0; st < 6; ++st) {
    std::vector<float> w(in.begin() + size_t(st) * n_in, in.begin() + size_t(st + 1) * n_in);
    int off = s.process(w.data(), out.data());
    worst = std::max(worst, max_abs_diff(out.data(), exp.data() + size_t(st) * block, block));
    std::printf("  step %d offset %d\n", st, off);
  }
  std::printf("  worst |err| over 6 blocks: %.2e\n", worst);
  CHECK(worst < 1e-4, "SOLA mismatch %.2e", worst);
}

// -------------------------------------------------------------------- retrieval
static void test_retrieval() {
  std::printf("[retrieval]\n");
  RetrievalIndex idx;
  std::string err;
  CHECK(idx.load(g_dir + "/idx_small.bin", &err), "load: %s", err.c_str());
  CHECK(idx.count() == 3000 && idx.dim() == 768, "dims");
  CHECK(RetrievalIndex::half_to_float(0x3C00) == 1.0f && RetrievalIndex::half_to_float(0xC000) == -2.0f &&
        RetrievalIndex::half_to_float(0x0001) > 0 && RetrievalIndex::half_to_float(0x0001) < 1e-7f, "half_to_float");
  auto q = load<float>("retr_in.f32");
  auto exp = load<float>("retr_expect.f32");
  for (int threads : {1, 3}) {
    std::vector<float> f = q;
    idx.blend(f.data(), 8, 0.75f, threads);
    const double d = max_abs_diff(f.data(), exp.data(), f.size());
    std::printf("  blend threads=%d: max err %.2e\n", threads, d);
    CHECK(d < 2e-3, "retrieval mismatch (threads=%d) %.2e", threads, d);
  }
  std::vector<float> f = q;
  idx.blend(f.data(), 8, 0.f, 1);
  CHECK(max_abs_diff(f.data(), q.data(), f.size()) == 0, "rate 0 is a no-op");
}

static void test_rng() {
  std::printf("[rng]\n");
  Rng r(42);
  std::vector<float> v(1000000);
  r.fill_normal(v.data(), v.size());
  double m = 0, s = 0;
  for (float x : v) m += x;
  m /= v.size();
  for (float x : v) s += (x - m) * (x - m);
  s /= v.size();
  std::printf("  mean %.4f var %.4f\n", m, s);
  CHECK(std::fabs(m) < 0.01 && std::fabs(s - 1.0) < 0.01, "normal stats");
}

int main(int argc, char** argv) {
  g_dir = argc > 1 ? argv[1] : "/tmp/rvc_vectors";
  test_resamplers();
  test_sine();
  test_pitch();
  test_sola();
  test_retrieval();
  test_rng();
  std::printf("\n%d checks passed, %d failed\n", g_pass, g_fail);
  return g_fail ? 1 : 0;
}
