// Streaming neural denoiser (DeepFilterNet-style single-graph ONNX): 480-sample frames @48 kHz,
// inputs {input_frame[480], states[45304], atten_lim_db}, outputs {enhanced_audio_frame[480], new_states, lsnr[1]}.
// Runs on the CPU EP. Missing/invalid model -> available() is false and the engine keeps the spectral isolator.
#pragma once
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "ort_graph.h"

namespace rvc {

class NeuralDenoiser {
 public:
  static constexpr int kFrame = 480, kStates = 45304;

  bool init(Ort::Env& env, const std::string& path, const BackendConfig& cfg, std::string* err) {
    using T = ONNXTensorElementDataType;
    const T F32 = ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;
    states_.assign(kStates, 0.f); new_states_.assign(kStates, 0.f); in_.assign(kFrame, 0.f); out_.assign(kFrame, 0.f);
    BackendConfig b = cfg; b.kind = BackendConfig::Kind::Cpu; b.cpu_threads = 1;  // ~100 tiny ops per frame: a thread pool costs more in wake-ups than it saves
    if (!g_.init(env, path, "denoiser",
                 {{"input_frame", {kFrame}, F32}, {"states", {kStates}, F32}, {"atten_lim_db", {}, F32}},
                 {{"enhanced_audio_frame", {kFrame}, F32}, {"new_states", {kStates}, F32}, {"lsnr", {1}, F32}},
                 b, /*on_npu=*/false, err)) return false;
    g_.bind_input(0, in_.data()); g_.bind_input(1, states_.data()); g_.bind_input(2, &atten_);
    g_.bind_output(0, out_.data()); g_.bind_output(1, new_states_.data()); g_.bind_output(2, &lsnr_);
    ok_ = true;
    for (int i = 0; i < 8; ++i) { g_.run(); }  // first runs allocate / page in weights: keep that out of the stream
    reset();
    return true;
  }
  bool available() const { return ok_; }
  void reset() { std::fill(states_.begin(), states_.end(), 0.f); }

  // strength 0..1 -> max attenuation (dB); >= 0.95 = unlimited (model's full suppression).
  void set_strength(float s) { atten_ = s >= 0.95f ? 0.f : 6.f + 54.f * std::max(s, 0.f); }

  // In place; n must be a multiple of 480 (a block is block*480 samples).
  void process(float* x, int n) {
    for (int o = 0; o + kFrame <= n; o += kFrame) {
      std::memcpy(in_.data(), x + o, sizeof(float) * kFrame);
      g_.run();
      std::memcpy(x + o, out_.data(), sizeof(float) * kFrame);
      std::memcpy(states_.data(), new_states_.data(), sizeof(float) * kStates);
    }
  }
  float lsnr() const { return lsnr_; }

 private:
  Graph g_;
  bool ok_ = false;
  float atten_ = 0.f, lsnr_ = 0.f;
  std::vector<float> states_, new_states_, in_, out_;
};

}  // namespace rvc
