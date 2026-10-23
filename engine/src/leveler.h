// Blip gate on the 48 kHz mic stream (10 ms frames), run after the denoiser and before the absolute noise gate. No gain is ever applied:
//  1. speech detector: frame RMS vs a tracked noise floor (frozen while speech, so long talking never raises it);
//  2. blip rejection: a speech run shorter than min_run frames is muted (clicks, taps, breaths, denoiser leftovers),
//     with a short hold so word endings are not clipped;
#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>

namespace rvc {

class InputConditioner {
 public:
  static constexpr int kFrame = 480;

  void reset() { *this = InputConditioner(); }

  // x: n samples, n a multiple of 480. strength 0 = off.
  void process(float* x, int n, float strength) {
    if (strength <= 0.f) return;
    const int F = n / kFrame;
    if (F <= 0 || F > kMaxF) return;
    float rms[kMaxF]; bool raw[kMaxF], keep[kMaxF];
    for (int i = 0; i < F; ++i) {
      double s = 0;
      for (int j = 0; j < kFrame; ++j) s += double(x[i * kFrame + j]) * x[i * kFrame + j];
      rms[i] = float(std::sqrt(s / kFrame));
    }
    // 1. speech / floor
    for (int i = 0; i < F; ++i) {
      if (floor_ < 0.f) floor_ = std::max(rms[i], 1e-5f);
      raw[i] = rms[i] > std::max(floor_ * 3.f, 3e-4f);
      if (!raw[i]) {
        floor_ = rms[i] < floor_ ? floor_ + 0.3f * (rms[i] - floor_) : floor_ + 0.02f * (rms[i] - floor_);
        floor_ = std::max(floor_, 1e-5f);
        run_ = 0;
      } else if (++run_ > 300) {
        floor_ *= 1.01f;  // never let a misjudged floor latch "speech" forever (3 s)
      }
    }
    // 2. blip rejection
    const int min_run = strength > 0.f ? 2 + int(std::lround(4.f * strength)) : 1;
    for (int i = 0; i < F; ++i) keep[i] = false;
    for (int i = 0; i < F;) {
      if (!raw[i]) { ++i; continue; }
      int j = i; while (j < F && raw[j]) ++j;
      const int len = j - i;
      const bool at_start = i == 0, at_end = j == F;
      const bool ok = len >= min_run || (at_start && tail_ + len >= min_run) || (at_end && len >= 2);
      if (ok) for (int k = i; k < j; ++k) keep[k] = true;
      i = j;
    }
    {  // run length carried over the block boundary (counts raw frames at the end of this block)
      int t = 0; for (int i = F - 1; i >= 0 && raw[i]; --i) ++t;
      tail_ = (t == F) ? tail_ + t : t;
    }
    // 3. gate (with hold), per frame with linear ramps
    for (int i = 0; i < F; ++i) {
      if (keep[i]) hold_ = kHold; else if (hold_ > 0) --hold_;
      const float g_gate = (strength > 0.f && !keep[i] && hold_ == 0) ? 0.f : 1.f;
      const float g0 = prev_, g1 = g_gate;
      float* f = x + i * kFrame;
      for (int j = 0; j < kFrame; ++j) {
        float v = f[j] * (g0 + (g1 - g0) * float(j + 1) / kFrame);
        f[j] = v;
      }
      prev_ = g1;
    }
  }

 private:
  static constexpr int kMaxF = 64, kHold = 12;
  float floor_ = -1.f, prev_ = 1.f;
  int run_ = 0, tail_ = 0, hold_ = 0;
};

}  // namespace rvc
