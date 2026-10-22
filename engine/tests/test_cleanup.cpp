// Host tests for VoiceIsolator and stabilize_f0 (no ONNX Runtime needed).
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include "../src/cleanup.h"
#include "../src/dsp.h"

static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { ++fails; std::printf("FAIL %s:%d  ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static double rms(const std::vector<float>& x, size_t a, size_t b) {
  double s = 0; for (size_t i = a; i < b; ++i) s += double(x[i]) * x[i];
  return std::sqrt(s / double(b - a));
}
static double db(double r) { return 20.0 * std::log10(r + 1e-12); }

static void test_isolator(float strength) {
  const int sr = 16000;
  const size_t N = size_t(sr) * 6;
  std::mt19937 rng(1);
  std::normal_distribution<float> nd(0.f, 1.f);
  std::vector<float> clean(N, 0.f), x(N);
  // "speech": 150 Hz harmonic complex with a syllable envelope, 2.0-2.6 s and 3.5-4.2 s, ~ -12 dBFS
  for (size_t i = 0; i < N; ++i) {
    const double t = double(i) / sr;
    const bool on = (t > 2.0 && t < 2.6) || (t > 3.5 && t < 4.2);
    if (!on) continue;
    double v = 0;
    for (int h = 1; h <= 20; ++h) v += std::sin(2 * M_PI * 150.0 * h * t) / h;
    clean[i] = float(0.25 * v * (0.6 + 0.4 * std::sin(2 * M_PI * 4.0 * t)));
  }
  const float sigma = 0.01f;  // -40 dBFS white-ish noise (fan / hiss)
  for (size_t i = 0; i < N; ++i) x[i] = clean[i] + sigma * nd(rng);
  std::vector<float> y = x;
  rvc::VoiceIsolator iso;
  iso.set_strength(strength);
  for (size_t o = 0; o < N; o += 1600) iso.process(y.data() + o, std::min<size_t>(1600, N - o));
  const size_t D = rvc::VoiceIsolator::latency_samples();
  for (float v : y) CHECK(std::isfinite(v), "non finite output");
  // noise-only segment 1.0-1.8 s (after warm-up) and 4.6-5.8 s
  const double n_in = db(rms(x, 16000, 28800)), n_out = db(rms(y, 16000 + D, 28800 + D));
  const double n_in2 = db(rms(x, 73600, 92800)), n_out2 = db(rms(y, 73600 + D, 92800 + D));
  // speech segment, aligned for the delay (compare against the clean reference)
  const double s_in = db(rms(clean, 35200, 40000)), s_out = db(rms(y, 35200 + D, 40000 + D));
  std::printf("strength %.2f: noise %.1f -> %.1f dBFS (%.1f dB), later %.1f -> %.1f, speech %.1f -> %.1f dBFS\n",
              strength, n_in, n_out, n_in - n_out, n_in2, n_out2, s_in, s_out);
  CHECK(n_in - n_out > 15.0, "noise only reduced by %.1f dB (want > 15)", n_in - n_out);
  CHECK(n_in2 - n_out2 > 15.0, "late noise only reduced by %.1f dB", n_in2 - n_out2);
  CHECK(std::fabs(s_out - s_in) < 2.5, "speech level changed by %.1f dB", s_out - s_in);
}

static void test_isolator_silence_and_levels() {
  rvc::VoiceIsolator iso;
  std::vector<float> z(16000, 0.f);
  iso.process(z.data(), z.size());
  for (float v : z) CHECK(v == 0.f, "silence must stay silent");
  // chunk sizes that are not a multiple of the hop must never underflow
  std::vector<float> a(1000, 0.1f);
  for (int i = 0; i < 200; ++i) { iso.process(a.data(), 1000 - (i % 7)); }
}

static void test_stabilizer() {
  const int n = 16;
  float hist[9]; for (float& h : hist) h = 200.f;
  // 1) steady 200 Hz with an octave-up error, an octave-down error and a +7 st spike
  float f[n]; for (float& v : f) v = 200.f;
  f[3] = 400.f; f[7] = 100.f; f[11] = 300.f; f[13] = 2000.f;
  rvc::stabilize_f0(f, n, hist, 9, 0.6f);
  for (int i = 0; i < n; ++i) {
    if (f[i] == 0.f) continue;
    CHECK(std::fabs(12.0 * std::log2(f[i] / 200.0)) < 0.7, "frame %d -> %.1f Hz", i, f[i]);
  }
  CHECK(f[3] > 0 && f[7] > 0, "octave errors should be folded, not dropped");
  CHECK(f[11] == 0.f, "+7 st spike should be rejected, got %.1f", f[11]);
  CHECK(f[13] == 0.f, "2000 Hz is out of range");
  // 2) a real glide 200 -> 260 Hz (about 0.4 st/frame) must survive
  float g[n]; for (int i = 0; i < n; ++i) g[i] = 200.f * std::pow(1.3f, float(i) / 15.f);
  float g0[n]; for (int i = 0; i < n; ++i) g0[i] = g[i];
  rvc::stabilize_f0(g, n, hist, 9, 0.6f);
  for (int i = 0; i < n; ++i) CHECK(std::fabs(12.0 * std::log2(g[i] / g0[i])) < 0.35, "glide frame %d moved %.2f st", i, 12.0 * std::log2(g[i] / g0[i]));
  // 3) after a pause the pitch may legitimately be somewhere else (gap widens the tolerance)
  float h[n] = {0}; for (int i = 8; i < n; ++i) h[i] = 330.f;
  float hz[9] = {200, 200, 200, 0, 0, 0, 0, 0, 0};
  rvc::stabilize_f0(h, n, hz, 9, 0.6f);
  CHECK(h[8] > 0 && std::fabs(12.0 * std::log2(h[8] / 330.0)) < 1.0, "new register after a pause: %.1f", h[8]);
  // 4) unvoiced frames stay unvoiced, strength 0 is a no-op
  float u[n] = {0}; rvc::stabilize_f0(u, n, hist, 9, 0.6f);
  for (float v : u) CHECK(v == 0.f, "unvoiced changed");
  float k[n]; for (float& v : k) v = 777.f; k[2] = 5000.f;
  rvc::stabilize_f0(k, n, hist, 9, 0.f);
  CHECK(k[2] == 5000.f, "strength 0 must not touch anything");
  // 5) no history at all
  float e[n]; for (float& v : e) v = 180.f; e[5] = 360.f;
  float none[9] = {0};
  rvc::stabilize_f0(e, n, none, 9, 1.0f);
  CHECK(e[5] > 0 && std::fabs(12.0 * std::log2(e[5] / 180.0)) < 0.7, "octave error without history: %.1f", e[5]);
}

static void test_pitchcache_split() {
  rvc::PitchCache a(64), b(64);
  std::vector<int64_t> c(16); std::vector<float> f(16);
  for (int i = 0; i < 16; ++i) { c[i] = i + 1; f[i] = 100.f + i; }
  a.update(c.data(), f.data(), 16, 10);
  b.shift(10); b.write_tail(c.data() + 3, f.data() + 3, 12);
  int64_t ca[64], cb[64]; float fa[64], fb[64];
  a.tail(64, ca, fa); b.tail(64, cb, fb);
  for (int i = 0; i < 64; ++i) CHECK(ca[i] == cb[i] && fa[i] == fb[i], "PitchCache split differs at %d", i);
  float t[8]; a.tail_f0(8, t);
  for (int i = 0; i < 8; ++i) CHECK(t[i] == fa[56 + i], "tail_f0");
}

int main() {
  test_isolator(0.3f);
  test_isolator(0.5f);
  test_isolator(1.0f);
  test_isolator_silence_and_levels();
  test_stabilizer();
  test_pitchcache_split();
  std::printf(fails ? "FAILED (%d)\n" : "OK\n", fails);
  return fails ? 1 : 0;
}
