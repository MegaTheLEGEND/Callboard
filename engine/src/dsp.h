// Realtime DSP building blocks for the RVC engine.
// Pure C++17, no dependencies; every function here is unit-tested on the host
// against Python reference vectors (engine/tests, export/gen_test_vectors.py).
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace rvc {

// ---------------------------------------------------------------- resampling
// Windowed-sinc (Kaiser) polyphase kernel for a rational ratio L/M
// (output_rate = input_rate * L / M).
struct ResamplerKernel {
  int L = 1, M = 1;
  int N = 0;               // taps at the upsampled rate (odd)
  int center = 0;          // (N-1)/2
  std::vector<float> h;    // gain-normalised (includes the factor L)
  static ResamplerKernel design(int L, int M, int half_len_in = 16);
};

// Causal streaming resampler (used for mic 48k -> 16k). Group delay is
// `kernel.center / L` input samples (1 ms for 48k->16k).
class StreamResampler {
 public:
  explicit StreamResampler(const ResamplerKernel& k);
  void reset();
  size_t max_out(size_t n_in) const { return n_in * k_.L / k_.M + 2; }
  // returns the number of samples written to `out`
  size_t process(const float* in, size_t n_in, float* out);

 private:
  ResamplerKernel k_;
  int K_;                       // taps per phase
  std::vector<float> hist_;     // last K-1 input samples
  std::vector<float> ext_;      // scratch: hist + block
  long pos_ = 0;                // next output position (upsampled units, rel. to block start)
};

// Stateless, zero-phase block resample (used for synth output 40k -> 48k, where
// consecutive blocks overlap and are stitched by SOLA). Returns n_out samples.
void resample_block(const ResamplerKernel& k, const float* in, size_t n_in, float* out,
                    size_t n_out);

// -------------------------------------------------------------- NSF source
// Bit-for-bit port of export/sinegen_ref.py::sine_excitation.
// f0: L values (Hz, 0 = unvoiced). noise: L*upp N(0,1) samples or nullptr (=0).
void sine_excitation(const float* f0, int L, int sr, int upp, float lin_w, float lin_b,
                     const float* noise, float* out);

// ------------------------------------------------------------------- pitch
// FP32 FCPE 'local_argmax' decode: latent[frames][bins] -> f0 Hz (0 = unvoiced).
void fcpe_decode(const float* latent, int frames, int bins, const float* cent_table,
                 float threshold, int half_window, bool zero_pad, float* f0_out);

// np.interp-style fill of unvoiced (==0) frames; no-op if nothing is voiced.
void interp_unvoiced(float* f0, int n);

// RVC get_f0_post: Hz -> coarse index 1..255 (f0_min=50, f0_max=1100).
void f0_to_coarse(const float* f0, int n, int64_t* coarse);

// Rolling pitch history, mirrors rtrvc.py cache_pitch / cache_pitchf.
class PitchCache {
 public:
  explicit PitchCache(int capacity = 1024);
  void clear();
  // Shift by `shift` frames, then write pitch[3 : n-1] to the end
  // (rtrvc.py: cache[4-n:] = pitch[3:-1]).
  void update(const int64_t* coarse, const float* f0, int n, int shift);
  void tail(int n, int64_t* coarse_out, float* f0_out) const;
  // update() split in two: shift the history left, then write `cnt` frames at the end (coarse may be null).
  void shift(int shift);
  void write_tail(const int64_t* coarse, const float* f0, int cnt);
  void tail_f0(int n, float* f0_out) const;

 private:
  std::vector<int64_t> coarse_;
  std::vector<float> f0_;
};

// -------------------------------------------------------------------- SOLA
// DDSP-SVC SOLA crossfade exactly as realtime_gui.py runs it.
class Sola {
 public:
  // all sizes in samples at the playback rate
  Sola(int block, int sola_buffer, int sola_search);
  void reset();
  // wav: block + sola_buffer + sola_search samples (modified in place).
  // Writes `block` samples to out. Returns the chosen offset.
  int process(float* wav, float* out);

 private:
  int block_, buf_, search_;
  std::vector<float> sola_buffer_, fade_in_, fade_out_;
};

// ------------------------------------------------------------------ misc
// Fast xoshiro128+ -> Box-Muller standard normal generator.
class Rng {
 public:
  explicit Rng(uint64_t seed = 0x9E3779B97F4A7C15ull);
  void fill_normal(float* out, size_t n);

 private:
  uint32_t s_[4];
  uint32_t next();
  float uniform();
  bool have_spare_ = false;
  float spare_ = 0.f;
};

// Per-10ms RMS noise gate (realtime_gui.py `threhold`): zero frames below `db`.
void noise_gate(float* x, size_t n, int zc, float db);

}  // namespace rvc
