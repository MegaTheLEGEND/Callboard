// Thin wrapper around one fixed-shape ONNX Runtime session.
//
// All QNN/HTP specifics live in ort_graph.cpp so that switching to Qualcomm's
// plugin-EP packaging (or any other execution provider) is a one-file change.
#pragma once
#include <memory>
#include <string>
#include <vector>

#include <onnxruntime_cxx_api.h>

namespace rvc {

struct BackendConfig {
  enum class Kind { Cpu, QnnHtp } kind = Kind::QnnHtp;
  std::string cache_dir;                       // where compiled HTP context binaries live
  std::string qnn_backend = "libQnnHtp.so";    // resolved by dlopen on Android
  std::string htp_perf_mode = "burst";         // burst | high_performance | sustained_high_performance
  bool htp_fp16 = true;                        // run the FP32 graph in FP16 on the HTP
  int cpu_threads = 2;
  bool strict_npu = false;                     // fail instead of silently falling back to CPU
  bool verbose = false;                        // ORT verbose log (shows node placement)
  std::string cache_tag;                       // changes -> compiled contexts are rebuilt
};

struct TensorSpec {
  std::string name;
  std::vector<int64_t> shape;
  ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;
};

class Graph {
 public:
  Graph() = default;
  // `on_npu=false` forces the CPU EP regardless of cfg (used for fcpe_mel).
  bool init(Ort::Env& env, const std::string& onnx_path, const std::string& tag,
            const std::vector<TensorSpec>& inputs, const std::vector<TensorSpec>& outputs,
            const BackendConfig& cfg, bool on_npu, std::string* err);

  // Zero-copy: the tensors wrap caller-owned memory for the lifetime of the Graph.
  void bind_input(size_t i, void* data);
  void bind_output(size_t i, void* data);
  void run();  // throws Ort::Exception

  // Optional: weights.bin (see pth_import.cpp) overriding the template's initializers at load.
  // Skipped automatically when a compiled NPU context (which already embeds weights) is reused.
  void set_weights(const std::string& path) { weights_path_ = path; }

  bool used_cached_context() const { return used_cache_; }
  const std::string& tag() const { return tag_; }

 private:
  Ort::Value make(const TensorSpec& s, void* data);

  std::unique_ptr<Ort::Session> session_;
  Ort::MemoryInfo mem_ = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
  Ort::RunOptions run_opts_;
  std::vector<TensorSpec> in_spec_, out_spec_;
  std::vector<std::string> in_names_s_, out_names_s_;
  std::vector<const char*> in_names_, out_names_;
  std::vector<Ort::Value> in_vals_, out_vals_;
  std::string tag_;
  bool used_cache_ = false;
  std::string weights_path_;
  std::vector<std::vector<float>> wbuf_;
  std::vector<Ort::Value> wvals_;
  void load_weights(Ort::SessionOptions& so);
};

}  // namespace rvc
