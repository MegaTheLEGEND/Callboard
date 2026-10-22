// Real-time glue between the audio callbacks and the (slower, bursty) inference.
//
//   mic callback --on_input()--> [in ring] --worker--> process_block() --> [out ring] --on_output()--> speaker
//
// Policy
//  * The output stays silent until the first block is ready, then `slack_ms`
//    of silence is queued ahead of it.  The slack is the jitter budget: a block
//    that takes longer than the previous one only underruns if it exceeds it.
//  * Every underrun makes the next block carry `adapt_step_ms` extra silence
//    (up to `adapt_max_ms`), so a glitchy start converges to a stable stream
//    instead of crackling forever.  Latency is the price; it is reported.
//  * If the inference thread falls behind (>= 2 blocks queued at the input), the
//    oldest block is dropped so latency stays bounded instead of growing.
// Oboe-free on purpose so it can be unit-tested with a simulated clock.
#pragma once
#include <atomic>
#include <functional>
#include <thread>
#include <vector>

#include "ring_buffer.h"

namespace rvc {

struct SessionStats {
  uint32_t underruns = 0;       // output starved
  uint32_t dropped_blocks = 0;  // input backlog discarded
  uint32_t out_overflows = 0;
  float out_level_ms = 0;       // queued playback
  float in_level_ms = 0;        // queued capture
  float slack_ms = 0;           // current silence margin (initial + adaptive)
  float in_peak = 0;            // mic peak 0..1 since the last stats() call
};

class VoiceSession {
 public:
  using ProcessFn = std::function<void(const float* in, float* out)>;

  VoiceSession(int block_samples, int sample_rate, ProcessFn fn);
  ~VoiceSession();

  void configure(float slack_ms, float adapt_step_ms = 10.f, float adapt_max_ms = 80.f);
  void start();   // spawns the inference thread
  void stop();
  bool running() const { return running_.load(); }

  // real-time safe (called from audio threads)
  void on_input(const float* mono, int n);
  void on_output(float* mono, int n);

  SessionStats stats() const;

 private:
  void worker();

  const int block_, sr_;
  ProcessFn fn_;
  SpscRing in_, out_;
  std::thread th_;
  std::atomic<bool> running_{false}, started_{false}, underrun_flag_{false};
  std::atomic<uint32_t> underruns_{0}, dropped_{0}, out_overflows_{0};
  std::atomic<float> slack_now_ms_{0};
  mutable std::atomic<float> in_peak_{0};
  float slack_ms_ = 20, adapt_step_ms_ = 10, adapt_max_ms_ = 80;
};

}  // namespace rvc
