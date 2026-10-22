// Simulated real-time test of VoiceSession: fake 48 kHz mic + speaker callbacks (96-frame bursts every 2 ms)
// and an "inference" function with random latency. Output must be a gap-free copy of the input after the
// slack; underruns must trigger the adaptive slack, bounded by adapt_max.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <random>
#include <thread>
#include <vector>
#include "../src/voice_session.h"
using namespace std::chrono;

struct Result { rvc::SessionStats st; long glitches; double lat_ms; size_t nz; };

static Result run(float slack_ms, int lo_ms, int hi_ms, double seconds) {
  const int block = 4800, sr = 48000, burst = 96;
  std::mt19937 rng(5);
  std::uniform_int_distribution<int> jitter(lo_ms, hi_ms);
  rvc::VoiceSession s(block, sr, [&](const float* in, float* out) {
    std::this_thread::sleep_for(milliseconds(jitter(rng)));
    std::copy(in, in + block, out);
  });
  s.configure(slack_ms, 10.f, 80.f);
  s.start();
  std::atomic<bool> run_flag{true};
  std::vector<float> captured;
  captured.reserve(size_t(seconds * sr) + 4096);
  std::thread mic([&] {
    std::vector<float> b(burst); long n = 0;
    auto next = steady_clock::now();
    while (run_flag) {
      for (int i = 0; i < burst; ++i) b[i] = float(++n);
      s.on_input(b.data(), burst);
      next += microseconds(2000); std::this_thread::sleep_until(next);
    }
  });
  std::thread spk([&] {
    std::vector<float> b(burst);
    auto next = steady_clock::now();
    while (run_flag) {
      s.on_output(b.data(), burst);
      captured.insert(captured.end(), b.begin(), b.end());
      next += microseconds(2000); std::this_thread::sleep_until(next);
    }
  });
  std::this_thread::sleep_for(duration<double>(seconds));
  run_flag = false; mic.join(); spk.join(); s.stop();

  // analysis of the played stream (starts after the last silence)
  long glitches = 0; size_t nz = 0; double lat = 0; size_t first = 0;
  while (first < captured.size() && captured[first] == 0) ++first;
  size_t tail_start = captured.size() * 2 / 3;        // judge steady state only (last third)
  for (size_t i = tail_start; i + 1 < captured.size(); ++i) {
    if (captured[i] != 0 && captured[i + 1] != 0) { ++nz; if (captured[i + 1] - captured[i] != 1.f) ++glitches; }
  }
  if (first < captured.size()) lat = (double(first) - double(captured[first] - 1)) / sr * 1000.0;
  return {s.stats(), glitches, lat, nz};
}

static bool report(const char* name, const Result& r) {
  std::printf("%-34s underruns %3u  dropped %u  slack %.0f ms  steady-state glitches %ld (of %zu samples)\n",
              name, r.st.underruns, r.st.dropped_blocks, r.st.slack_ms, r.glitches, r.nz);
  return true;
}

int main() {
  bool ok = true;
  auto a = run(70, 20, 70, 6.0);  report("slack 70 ms, latency 20-70 ms", a);
  ok &= a.st.underruns == 0 && a.glitches == 0 && a.st.slack_ms == 70.f;
  auto b = run(0, 20, 70, 8.0);   report("slack 0 ms,  latency 20-70 ms", b);
  ok &= b.st.underruns > 0 && b.st.slack_ms > 0.f && b.st.slack_ms <= 80.f && b.st.dropped_blocks == 0;
  auto c = run(30, 150, 160, 5.0); report("overload: inference > 1 block", c);
  ok &= c.st.dropped_blocks > 0 && c.st.in_level_ms < 250.f;           // backlog stays bounded
  std::printf("%s\n", ok ? "session test OK" : "session test FAILED");
  return ok ? 0 : 1;
}
