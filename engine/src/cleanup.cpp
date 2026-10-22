#include "cleanup.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace rvc {

// =================================================================== VoiceIsolator
namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kEps = 1e-10f;  // ~ -100 dBFS^2: floor for the noise PSD (16-bit quantisation noise is ~8e-11)
constexpr int kWarmHops = 60;   // first 300 ms: noise estimate follows the input fast, gate stays open
constexpr int kSpeechLo = 3, kSpeechHi = 128;  // FFT bins ~100 Hz .. 4 kHz (31.25 Hz/bin)
}  // namespace

VoiceIsolator::VoiceIsolator() {
  for (int i = 0; i < kWin; ++i) win_[i] = std::sqrt(0.5f * (1.f - std::cos(2.f * kPi * float(i) / float(kWin))));
  for (int k = 0; k < kFft / 2; ++k) {
    const double a = 2.0 * 3.14159265358979323846 * k / kFft;
    tw_re_[k] = float(std::cos(a));
    tw_im_[k] = float(-std::sin(a));
  }
  int bits = 0;
  while ((1 << bits) < kFft) ++bits;
  for (int i = 0; i < kFft; ++i) {
    int r = 0;
    for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
    bitrev_[i] = uint16_t(r);
  }
  noise_.assign(kBins, kEps);
  smooth_.assign(kBins, 0.f);
  prev_a2_.assign(kBins, 0.f);
  gain_.assign(kBins, 1.f);
  set_strength(0.5f);
  reset();
}

void VoiceIsolator::reset() {
  inq_.clear();
  outq_.assign(size_t(kWin), 0.f);  // priming: guarantees n samples out for n in, see process()
  acc_.assign(size_t(kWin), 0.f);
  std::fill(noise_.begin(), noise_.end(), kEps);
  std::fill(smooth_.begin(), smooth_.end(), 0.f);
  std::fill(prev_a2_.begin(), prev_a2_.end(), 0.f);
  warm_ = 0;
  hold_ = 0;
  open_ = true;
  gsm_ = 1.f;
}

void VoiceIsolator::set_strength(float s) {
  s = std::min(std::max(s, 0.f), 1.f);
  gmin_ = std::pow(10.f, -(8.f + 22.f * s) / 20.f);          // per-bin gain floor: -8 .. -30 dB
  over_ = 1.3f + 1.2f * s;                                    // noise over-estimate (min-tracker bias)
  gate_floor_ = std::pow(10.f, -(18.f + 30.f * s) / 20.f);   // closed-gate attenuation: -18 .. -48 dB
  t_open_ = 6.f + 6.f * s;                                    // dB above the noise floor to open
  t_close_ = t_open_ - 3.f;
  hold_hops_ = 30 + int(20.f * s);                            // 150 .. 250 ms
}

void VoiceIsolator::fft(float* re, float* im, bool inverse) const {
  for (int i = 0; i < kFft; ++i) {
    const int j = bitrev_[i];
    if (j > i) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
  }
  for (int len = 2; len <= kFft; len <<= 1) {
    const int half = len / 2, step = kFft / len;
    for (int i = 0; i < kFft; i += len) {
      for (int k = 0; k < half; ++k) {
        const float wr = tw_re_[k * step];
        const float wi = inverse ? -tw_im_[k * step] : tw_im_[k * step];
        const int a = i + k, b = a + half;
        const float vr = re[b] * wr - im[b] * wi;
        const float vi = re[b] * wi + im[b] * wr;
        re[b] = re[a] - vr; im[b] = im[a] - vi;
        re[a] += vr;        im[a] += vi;
      }
    }
  }
}

void VoiceIsolator::frame(const float* in) {
  float re[kFft], im[kFft];
  double e = 0;
  for (int i = 0; i < kWin; ++i) { re[i] = in[i] * win_[i]; e += double(in[i]) * in[i]; }
  for (int i = kWin; i < kFft; ++i) re[i] = 0.f;
  std::memset(im, 0, sizeof(im));
  fft(re, im, false);

  const bool digital_silence = e / kWin < 1e-10;  // < -100 dBFS rms: nothing to do, never opens the gate
  const float norm = 2.f / float(kWin);           // white noise of variance s^2 -> P ~ s^2 per bin
  float P[kBins], Neff[kBins];
  const bool warm = warm_ < kWarmHops;
  for (int k = 0; k < kBins; ++k) {
    P[k] = (re[k] * re[k] + im[k] * im[k]) * norm;
    smooth_[k] = (warm_ == 0) ? P[k] : 0.8f * smooth_[k] + 0.2f * P[k];
    float& n = noise_[k];
    if (warm) {
      n = (warm_ == 0) ? smooth_[k] : 0.9f * n + 0.1f * smooth_[k];
    } else if (smooth_[k] < n) {
      n = 0.7f * n + 0.3f * smooth_[k];          // fall fast
    } else {
      n = std::min(n * 1.002f, smooth_[k]);       // rise ~1.8 dB/s
    }
    n = std::max(n, kEps);
    Neff[k] = n * over_;
  }
  if (warm_ < kWarmHops) ++warm_;

  // ---- speech gate: is anything in the voice band clearly above the noise floor? ----
  double ps = 0, ns = 0;
  for (int k = kSpeechLo; k <= kSpeechHi; ++k) { ps += smooth_[k]; ns += Neff[k]; }
  const float r_db = 10.f * std::log10(float(ps / (ns + 1e-20)) + 1e-12f);
  if (warm) {
    open_ = true; hold_ = hold_hops_;
  } else if (digital_silence) {
    open_ = false; hold_ = 0;
  } else if (r_db > t_open_) {
    open_ = true; hold_ = hold_hops_;
  } else if (open_) {
    if (r_db > t_close_) hold_ = hold_hops_;
    else if (--hold_ <= 0) open_ = false;
  }
  const float target = open_ ? 1.f : gate_floor_;
  gsm_ += (target - gsm_) * (target > gsm_ ? 0.5f : 0.12f);  // ~10 ms attack, ~40 ms release

  // ---- per-bin Wiener gain, decision-directed a-priori SNR ----
  const float alpha = 0.92f;
  for (int k = 0; k < kBins; ++k) {
    const float gamma = P[k] / Neff[k];
    float xi = alpha * prev_a2_[k] / Neff[k] + (1.f - alpha) * std::max(gamma - 1.f, 0.f);
    gain_[k] = std::max(xi / (1.f + xi), gmin_);
  }
  for (int k = 0; k <= 2; ++k) gain_[k] = gmin_;  // below ~90 Hz: rumble / handling noise
  float g[kBins];
  g[0] = gain_[0]; g[kBins - 1] = gain_[kBins - 1];
  for (int k = 1; k < kBins - 1; ++k) g[k] = 0.25f * gain_[k - 1] + 0.5f * gain_[k] + 0.25f * gain_[k + 1];
  for (int k = 0; k < kBins; ++k) {
    prev_a2_[k] = g[k] * g[k] * P[k];
    g[k] *= gsm_;
  }

  // ---- apply, inverse FFT, overlap-add ----
  for (int k = 0; k < kBins; ++k) {
    re[k] *= g[k]; im[k] *= g[k];
    if (k > 0 && k < kFft / 2) { re[kFft - k] *= g[k]; im[kFft - k] *= g[k]; }
  }
  fft(re, im, true);
  const float scale = 0.5f / float(kFft);  // 1/N for the IFFT, 1/2: sum of Hann^2 at 75 % overlap
  for (int i = 0; i < kWin; ++i) acc_[size_t(i)] += re[i] * win_[i] * scale;
  outq_.insert(outq_.end(), acc_.begin(), acc_.begin() + kHop);
  std::memmove(acc_.data(), acc_.data() + kHop, sizeof(float) * (kWin - kHop));
  std::fill(acc_.begin() + (kWin - kHop), acc_.end(), 0.f);
}

void VoiceIsolator::process(float* x, size_t n) {
  inq_.insert(inq_.end(), x, x + n);
  size_t pos = 0;
  while (inq_.size() - pos >= size_t(kWin)) {
    frame(inq_.data() + pos);
    pos += kHop;
  }
  inq_.erase(inq_.begin(), inq_.begin() + long(pos));
  // outq_ always holds >= n samples: it starts with kWin priming zeros and each call adds
  // floor((I - kWin) / kHop) * kHop + kHop > I - kWin samples in total.
  if (outq_.size() < n) outq_.insert(outq_.begin(), n - outq_.size(), 0.f);  // defensive, never expected
  std::memcpy(x, outq_.data(), sizeof(float) * n);
  outq_.erase(outq_.begin(), outq_.begin() + long(n));
}

// ===================================================================== stabilize_f0
namespace {
inline float to_st(float hz) { return 12.f * std::log2(hz * 0.01f); }   // semitones re 100 Hz
inline float to_hz(float st) { return 100.f * std::exp2(st / 12.f); }
}  // namespace

void stabilize_f0(float* f0, int n, const float* hist, int nh, float strength) {
  if (strength <= 0.f || n <= 0) return;
  strength = std::min(strength, 1.f);
  n = std::min(n, 128);
  constexpr float kMinHz = 50.f, kMaxHz = 1100.f;
  for (int i = 0; i < n; ++i)
    if (f0[i] != 0.f && !(f0[i] >= kMinHz && f0[i] <= kMaxHz)) f0[i] = 0.f;  // also catches NaN

  // running reference = median of the last (up to 8) voiced frames of the history
  float ref = 0.f;
  bool have = false;
  int gap = 0;
  {
    float v[8];
    int c = 0;
    for (int j = nh - 1; j >= 0 && c < 8; --j)
      if (hist[j] > 0.f) v[c++] = to_st(hist[j]);
    if (c > 0) {
      std::sort(v, v + c);
      ref = v[c / 2];
      have = true;
      for (int j = nh - 1; j >= 0 && hist[j] <= 0.f; --j) ++gap;
    }
  }

  const float base_tol = 6.f - 3.5f * strength;  // semitones allowed between neighbouring frames
  for (int i = 0; i < n; ++i) {
    if (f0[i] <= 0.f) { if (have) ++gap; continue; }
    float s = to_st(f0[i]);
    if (!have) { ref = s; have = true; gap = 0; continue; }
    const float tol = std::min(base_tol + 0.8f * float(gap), 24.f);
    const float dev = s - ref;
    bool ok = std::fabs(dev) <= tol;
    if (!ok) {  // octave error? try x2, /2, x4, /4
      float best = 1e9f, bs = s;
      for (int k : {-2, -1, 1, 2}) {
        const float d2 = std::fabs(dev + 12.f * float(k));
        if (d2 < best) { best = d2; bs = s + 12.f * float(k); }
      }
      if (best <= tol) { s = bs; ok = true; }
    }
    if (!ok) { f0[i] = 0.f; ++gap; continue; }  // isolated jump: unvoiced, interpolated later
    ref += 0.6f * (s - ref);
    gap = 0;
    f0[i] = to_hz(s);
  }

  if (strength < 0.05f) return;
  float st[128], med[128];
  bool voiced[128];
  for (int i = 0; i < n; ++i) { voiced[i] = f0[i] > 0.f; st[i] = voiced[i] ? to_st(f0[i]) : 0.f; }
  const int half = strength > 0.5f ? 2 : 1;
  for (int i = 0; i < n; ++i) {
    if (!voiced[i]) continue;
    float w[5];
    int c = 0;
    for (int j = std::max(0, i - half); j <= std::min(n - 1, i + half); ++j)
      if (voiced[j]) w[c++] = st[j];
    for (int a = 1; a < c; ++a) {  // insertion sort, c <= 5
      const float key = w[a];
      int b = a - 1;
      while (b >= 0 && w[b] > key) { w[b + 1] = w[b]; --b; }
      w[b + 1] = key;
    }
    med[i] = w[c / 2];
  }
  const float a = 0.25f * strength;
  for (int i = 0; i < n; ++i) {
    if (!voiced[i]) continue;
    const float l = (i > 0 && voiced[i - 1]) ? med[i - 1] : med[i];
    const float r = (i + 1 < n && voiced[i + 1]) ? med[i + 1] : med[i];
    f0[i] = to_hz((1.f - 2.f * a) * med[i] + a * (l + r));
  }
}

}  // namespace rvc
