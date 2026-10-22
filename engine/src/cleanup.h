// Input clean-up for the voice engine: (1) a realtime "voice only" spectral suppressor that runs on the
// 16 kHz mic stream before HuBERT/FCPE see it, and (2) an f0 stabiliser that removes octave errors and
// random pitch spikes from the FCPE track. Pure C++17, no dependencies, host-testable
// (engine/tests/test_cleanup.cpp).
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace rvc {

// Streaming noise suppressor + speech gate at 16 kHz mono.
//   * 20 ms sqrt-Hann STFT, 5 ms hop (75 % overlap), 512-point FFT
//   * noise floor tracked per bin (fast-fall / slow-rise minimum tracker, so it follows fans, hiss, road
//     noise, hum and does not need a "silence" calibration)
//   * decision-directed Wiener gain (a-priori SNR), frequency-smoothed against musical noise
//   * broadband speech gate with hold: when nothing in 100 Hz-4 kHz stands clearly above the noise floor the
//     frame is faded to (almost) silence, so HuBERT hears silence instead of hiss it would turn into mumble.
// Does NOT separate a second talker or music: that needs a neural separator.
class VoiceIsolator {
 public:
  static constexpr int kWin = 320, kHop = 80, kFft = 512, kBins = kFft / 2 + 1;
  VoiceIsolator();
  void reset();
  // 0..1. 0.5 = moderate (about -19 dB residual noise), 1 = aggressive.
  void set_strength(float s);
  // In place. Output is delayed by latency_samples() (the first ones are zeros).
  void process(float* x, size_t n);
  static constexpr int latency_samples() { return kWin; }
  bool gate_open() const { return open_; }

 private:
  void frame(const float* in);
  void fft(float* re, float* im, bool inverse) const;

  float win_[kWin];
  float tw_re_[kFft / 2], tw_im_[kFft / 2];
  uint16_t bitrev_[kFft];
  std::vector<float> inq_, outq_, acc_;
  std::vector<float> noise_, smooth_, prev_a2_, gain_;
  int warm_ = 0, hold_ = 0;
  bool open_ = true;
  float gsm_ = 1.f;
  // derived from strength
  float gmin_ = 0.1f, over_ = 1.7f, gate_floor_ = 0.03f, t_open_ = 9.f, t_close_ = 6.f;
  int hold_hops_ = 40;
};

// Cleans one FCPE track (Hz per 10 ms frame, 0 = unvoiced) in place, BEFORE unvoiced interpolation and key shift.
//   * drops f0 outside 50..1100 Hz (the vocoder would otherwise synthesise it even though the coarse index clamps)
//   * folds octave errors (x2, /2, x4, /4) back onto the running pitch
//   * rejects isolated jumps (marks them unvoiced, interpolated afterwards); the allowed jump widens the longer
//     the pitch has been missing, so real register changes after a pause still pass
//   * 3/5-point median + light smoothing in the semitone domain to take the jitter off
// hist: the `nh` frames that precede f0[0] (older first), already stabilised; may be all zero.
// strength 0..1 (0 = untouched).
void stabilize_f0(float* f0, int n, const float* hist, int nh, float strength);

}  // namespace rvc
