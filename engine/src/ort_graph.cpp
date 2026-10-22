#include "ort_graph.h"

#include <sys/stat.h>

#include <cstdint>
#include <cstring>
#include <fstream>
#include <unordered_map>

namespace rvc {

static bool file_exists(const std::string& p) {
  struct stat st;
  return ::stat(p.c_str(), &st) == 0 && st.st_size > 0;
}

static size_t elem_size(ONNXTensorElementDataType t) {
  switch (t) {
    case ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT: return 4;
    case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64: return 8;
    case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32: return 4;
    default: return 4;
  }
}

void Graph::load_weights(Ort::SessionOptions& so) {
  std::ifstream f(weights_path_, std::ios::binary);
  if (!f) throw std::runtime_error("cannot open " + weights_path_);
  char magic[4]; uint32_t ver = 0, n = 0;
  f.read(magic, 4); f.read(reinterpret_cast<char*>(&ver), 4); f.read(reinterpret_cast<char*>(&n), 4);
  if (!f || memcmp(magic, "RVCW", 4) != 0 || ver != 1) throw std::runtime_error("bad weights.bin");
  wbuf_.reserve(n); wvals_.reserve(n);
  std::vector<std::vector<int64_t>> dims(n);
  for (uint32_t k = 0; k < n; ++k) {
    uint16_t nl = 0; uint8_t nd = 0;
    f.read(reinterpret_cast<char*>(&nl), 2);
    std::string name(nl, '\0'); f.read(name.data(), nl);
    f.read(reinterpret_cast<char*>(&nd), 1);
    dims[k].resize(nd); f.read(reinterpret_cast<char*>(dims[k].data()), 8 * nd);
    size_t cnt = 1; for (auto d : dims[k]) cnt *= size_t(d);
    wbuf_.emplace_back(cnt);
    f.read(reinterpret_cast<char*>(wbuf_.back().data()), std::streamsize(cnt * 4));
    if (!f) throw std::runtime_error("truncated weights.bin");
    wvals_.push_back(Ort::Value::CreateTensor<float>(mem_, wbuf_.back().data(), cnt, dims[k].data(), dims[k].size()));
    so.AddInitializer(name.c_str(), wvals_.back());
  }
}

Ort::Value Graph::make(const TensorSpec& s, void* data) {
  size_t n = 1;
  for (auto d : s.shape) n *= size_t(d);
  return Ort::Value::CreateTensor(mem_, data, n * elem_size(s.type), s.shape.data(),
                                  s.shape.size(), s.type);
}

bool Graph::init(Ort::Env& env, const std::string& onnx_path, const std::string& tag,
                 const std::vector<TensorSpec>& inputs, const std::vector<TensorSpec>& outputs,
                 const BackendConfig& cfg, bool on_npu, std::string* err) {
  tag_ = tag;
  in_spec_ = inputs;
  out_spec_ = outputs;
  try {
    Ort::SessionOptions so;
    so.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    so.SetIntraOpNumThreads(cfg.cpu_threads);
    so.SetInterOpNumThreads(1);
    so.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    so.AddConfigEntry("session.intra_op.allow_spinning", "0");  // don't burn cores while idle
    if (cfg.verbose) so.SetLogSeverityLevel(0);

    std::string model_path = onnx_path;
    const bool npu = on_npu && cfg.kind == BackendConfig::Kind::QnnHtp;
    if (npu) {
      // ---- QNN / Hexagon HTP -------------------------------------------------
      std::unordered_map<std::string, std::string> qnn;
      qnn["backend_path"] = cfg.qnn_backend;
      qnn["htp_performance_mode"] = cfg.htp_perf_mode;
      qnn["enable_htp_fp16_precision"] = cfg.htp_fp16 ? "1" : "0";
      qnn["htp_graph_finalization_optimization_mode"] = "3";  // slowest compile, fastest graph
      so.AppendExecutionProvider("QNN", qnn);
      so.AddConfigEntry("session.disable_cpu_ep_fallback", cfg.strict_npu ? "1" : "0");

      // Compiling a graph for the HTP takes seconds to minutes. Do it once, store
      // the finalised context binary, and load that on every later start.
      const std::string ctx = cfg.cache_dir + "/" + tag + "_" + cfg.cache_tag + "_ctx.onnx";
      if (!cfg.cache_dir.empty()) {
        if (file_exists(ctx)) {
          model_path = ctx;
          used_cache_ = true;
        } else {
          so.AddConfigEntry("ep.context_enable", "1");
          so.AddConfigEntry("ep.context_file_path", ctx.c_str());
          so.AddConfigEntry("ep.context_embed_mode", "1");
        }
      }
    }
    if (!weights_path_.empty() && !used_cache_) load_weights(so);
    session_ = std::make_unique<Ort::Session>(env, model_path.c_str(), so);
  } catch (const Ort::Exception& e) {
    if (err) *err = tag + ": " + e.what();
    return false;
  } catch (const std::exception& e) {
    if (err) *err = tag + ": " + e.what();
    return false;
  }

  // sanity: graph I/O must be exactly what the pack geometry promises
  Ort::AllocatorWithDefaultOptions alloc;
  if (session_->GetInputCount() != inputs.size() || session_->GetOutputCount() != outputs.size()) {
    if (err) *err = tag + ": unexpected input/output count";
    return false;
  }
  for (size_t i = 0; i < inputs.size(); ++i) {
    auto nm = session_->GetInputNameAllocated(i, alloc);
    if (inputs[i].name != nm.get()) { if (err) *err = tag + ": input name '" + nm.get() + "'"; return false; }
  }
  for (size_t i = 0; i < outputs.size(); ++i) {
    auto nm = session_->GetOutputNameAllocated(i, alloc);
    if (outputs[i].name != nm.get()) { if (err) *err = tag + ": output name '" + nm.get() + "'"; return false; }
  }
  for (auto& s : inputs) in_names_s_.push_back(s.name);
  for (auto& s : outputs) out_names_s_.push_back(s.name);
  for (auto& s : in_names_s_) in_names_.push_back(s.c_str());
  for (auto& s : out_names_s_) out_names_.push_back(s.c_str());
  in_vals_.reserve(inputs.size());
  out_vals_.reserve(outputs.size());
  for (size_t i = 0; i < inputs.size(); ++i) in_vals_.emplace_back(nullptr);
  for (size_t i = 0; i < outputs.size(); ++i) out_vals_.emplace_back(nullptr);
  return true;
}

void Graph::bind_input(size_t i, void* data) { in_vals_[i] = make(in_spec_[i], data); }
void Graph::bind_output(size_t i, void* data) { out_vals_[i] = make(out_spec_[i], data); }

void Graph::run() {
  session_->Run(run_opts_, in_names_.data(), in_vals_.data(), in_vals_.size(), out_names_.data(),
                out_vals_.data(), out_vals_.size());
}

}  // namespace rvc
