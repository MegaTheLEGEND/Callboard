// Realtime RVC pipeline: one call to process_block() == one 100 ms (default) step.
// Platform-independent: audio I/O (Oboe) lives elsewhere; this class only needs
// ONNX Runtime and the DSP layer, so it also runs on a desktop for testing.
#pragma once
#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "denoiser.h"
#include "cleanup.h"
#include "dsp.h"
#include "ort_graph.h"
#include "retrieval.h"

namespace rvc {

struct PackConfig {
  int block = 10, extra = 50, crossfade = 5, sola_search = 1;
  int tgt_sr = 40000, upp = 400, feat_dim = 768, inter_channels = 192;
  int window = 66, sola_buffer = 4, skip_head = 50, return_length = 15;
  int flow_head = 26, dec_head = 24;
  int hubert_samples = 10560, hubert_frames = 32;
  int fcpe_samples = 2400, fcpe_frames = 16, fcpe_bins = 360, fcpe_half_window = 4;
  float fcpe_threshold = 0.03f;
  bool fcpe_zero_pad = true;  // RMVPE decode (RVC to_local_average_cents); false = torchfcpe clamp
  int out_samples = 6000;
  float source_lin_w = 1.f, source_lin_b = 0.f;
  bool has_index = false;
  static bool load(const std::string& path, PackConfig* out, std::string* err);
};

// Live-tunable parameters (safe to change from any thread).
struct RuntimeParams {
  std::atomic<float> pitch_semitones{0.f};
  std::atomic<float> index_rate{0.5f};     // 0 disables retrieval (saves ~10 ms)
  std::atomic<float> gate_db{-60.f};       // input noise gate; <= -60 disables
  std::atomic<bool> synth_noise{true};     // false = deterministic, slightly cleaner
  std::atomic<float> isolate{0.f};         // 0 = off, 0..1 voice-only noise suppression on the 16 kHz mic stream (+20 ms)
  std::atomic<float> pitch_stab{0.f};      // 0 = off, 0..1 f0 octave/spike rejection + smoothing
  std::atomic<float> mic_gain{1.f};        // linear input gain, applied by VoiceSession before the engine sees the mic
};

struct StageTimes {  // milliseconds, last block
  float pre = 0, pitch = 0, hubert = 0, retrieval = 0, synth = 0, post = 0, total = 0;
};

class Engine {
 public:
  static constexpr int kDeviceSr = 48000;
  static constexpr int kDeviceZc = kDeviceSr / 100;

  Engine();
  ~Engine();

  bool init(const std::string& pack_dir, const BackendConfig& cfg, std::string* err);
  bool ready() const { return ready_; }

  // device-rate (48 kHz) samples consumed / produced per step
  int block_samples() const { return pc_.block * kDeviceZc; }
  // algorithmic latency floor in ms (block + crossfade lookahead); compute time is on top
  float latency_floor_ms() const { return float((pc_.block + pc_.sola_buffer + pc_.sola_search) * 10); }
  const PackConfig& pack() const { return pc_; }

  // in48/out48: block_samples() mono float samples each. Not thread-safe with itself.
  void process_block(const float* in48, float* out48);
  void reset();

  RuntimeParams params;
  StageTimes last() const { return last_; }
  bool npu_cached(const char* tag) const;

  // Retrieval (index) diagnostics, for the stats panel.
  bool index_loaded() const { return pc_.has_index && index_.loaded(); }
  int index_count() const { return index_.count(); }
  bool index_ran() const { return index_ran_.load(std::memory_order_relaxed); }    // last block used the index
  float index_shift() const { return index_shift_.load(std::memory_order_relaxed); }  // |blended - original| / |original|
  float index_ms() const { return index_ms_.load(std::memory_order_relaxed); }        // time of the k-NN search + blend

 private:
  void run_fcpe();

  PackConfig pc_;
  bool ready_ = false;
  Ort::Env env_{ORT_LOGGING_LEVEL_WARNING, "rvc"};
  Graph fcpe_mel_, fcpe_net_, hubert_, synth_;
  RetrievalIndex index_;
  BackendConfig cfg_;

  std::unique_ptr<StreamResampler> mic_rs_;
  ResamplerKernel out_kernel_;
  std::unique_ptr<Sola> sola_;
  PitchCache pitch_cache_;
  PitchCache raw_f0_{256};      // stabilised f0 before interpolation/key shift, aligned with pitch_cache_
  VoiceIsolator isolator_;
  NeuralDenoiser denoiser_;
  bool dn_active_ = false;
  bool iso_active_ = false, stab_active_ = false;
  Rng rng_;

  std::vector<float> cents_;
  std::vector<float> tmp48_, tmp16_, in16_;
  std::vector<float> mel_, latent_, f0raw_;
  std::vector<int64_t> coarse_raw_;
  std::vector<float> feats_;
  std::vector<int64_t> pitch_;
  std::vector<float> pitchf_, har_, sine_noise_, noise_, audio_, wav48_;
  StageTimes last_;
  std::atomic<bool> index_ran_{false};
  std::atomic<float> index_shift_{0.f}, index_ms_{0.f};
  std::vector<float> blend_pre_;
};

}  // namespace rvc
