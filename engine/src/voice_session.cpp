#include "voice_session.h"

#include <chrono>
#include <cmath>
#include <cstring>

#if defined(__ANDROID__)
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace rvc {

VoiceSession::VoiceSession(int block, int sr, ProcessFn fn)
    : block_(block), sr_(sr), fn_(std::move(fn)) {
  in_.resize(size_t(block) * 4);
  out_.resize(size_t(block) * 6);
}

VoiceSession::~VoiceSession() { stop(); }

void VoiceSession::configure(float slack_ms, float step, float max) {
  slack_ms_ = slack_ms;
  adapt_step_ms_ = step;
  adapt_max_ms_ = max;
}

void VoiceSession::start() {
  if (running_.exchange(true)) return;
  started_.store(false);
  underrun_flag_.store(false);
  underruns_ = 0; dropped_ = 0; out_overflows_ = 0;
  slack_now_ms_.store(slack_ms_);
  in_.skip(in_.available());
  out_.skip(out_.available());
  th_ = std::thread([this] { worker(); });
}

void VoiceSession::stop() {
  if (!running_.exchange(false)) return;
  if (th_.joinable()) th_.join();
}

void VoiceSession::on_input(const float* mono, int n) {
  float pk = 0.f;
  for (int i = 0; i < n; ++i) { const float a = std::fabs(mono[i]); if (a > pk) pk = a; }
  float cur = in_peak_.load(std::memory_order_relaxed);
  while (pk > cur && !in_peak_.compare_exchange_weak(cur, pk, std::memory_order_relaxed)) {}
  in_.write(mono, size_t(n));
}

void VoiceSession::on_output(float* y, int n) {
  if (!started_.load(std::memory_order_acquire)) {
    std::memset(y, 0, sizeof(float) * size_t(n));
    return;
  }
  const size_t got = out_.read(y, size_t(n));
  if (got < size_t(n)) {
    std::memset(y + got, 0, sizeof(float) * (size_t(n) - got));
    underruns_.fetch_add(1, std::memory_order_relaxed);
    underrun_flag_.store(true, std::memory_order_relaxed);
  }
}

SessionStats VoiceSession::stats() const {
  SessionStats s;
  s.underruns = underruns_.load();
  s.dropped_blocks = dropped_.load();
  s.out_overflows = out_overflows_.load();
  s.out_level_ms = 1000.f * float(out_.available()) / float(sr_);
  s.in_level_ms = 1000.f * float(in_.available()) / float(sr_);
  s.slack_ms = slack_now_ms_.load();
  s.in_peak = in_peak_.exchange(0.f, std::memory_order_relaxed);
  return s;
}

void VoiceSession::worker() {
#if defined(__ANDROID__)
  // best effort: ask for the audio scheduling class (may be refused, that's fine)
  setpriority(PRIO_PROCESS, pid_t(syscall(SYS_gettid)), -19);
#endif
  std::vector<float> inb(static_cast<size_t>(block_));
  std::vector<float> outb(static_cast<size_t>(block_));
  float adapt_ms = 0.f;
  const auto ms_to_samples = [this](float ms) { return size_t(ms * 0.001f * float(sr_)); };

  while (running_.load(std::memory_order_relaxed)) {
    if (in_.available() < size_t(block_)) {
      std::this_thread::sleep_for(std::chrono::microseconds(400));
      continue;
    }
    // inference fell behind: keep only the newest block so latency stays bounded
    while (in_.available() >= size_t(block_) * 2) {
      in_.skip(size_t(block_));
      dropped_.fetch_add(1, std::memory_order_relaxed);
    }
    in_.read(inb.data(), size_t(block_));
    fn_(inb.data(), outb.data());

    if (!started_.load(std::memory_order_relaxed)) {
      out_.write_zeros(ms_to_samples(slack_ms_));
      out_.write(outb.data(), size_t(block_));
      started_.store(true, std::memory_order_release);
      continue;
    }
    if (underrun_flag_.exchange(false) && adapt_ms + adapt_step_ms_ <= adapt_max_ms_) {
      adapt_ms += adapt_step_ms_;
      out_.write_zeros(ms_to_samples(adapt_step_ms_));
      slack_now_ms_.store(slack_ms_ + adapt_ms);
    }
    if (out_.write(outb.data(), size_t(block_)) < size_t(block_))
      out_overflows_.fetch_add(1, std::memory_order_relaxed);
  }
}

}  // namespace rvc
