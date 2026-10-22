// On-device RVC .pth reader: no torch, no pickle execution (a small pickle VM that only builds data).
// pth_import() folds weight-norm, picks the speaker row and writes weights.bin + engine.cfg for a voice;
// the weight-less synth template (built once on the PC by export/build_base.py) receives them at load time.
#pragma once
#include <string>

namespace rvc {

struct PthInfo {
  std::string version;  // "v1" | "v2"
  int sr = 0, f0 = 1, speakers = 0;
  std::string reason;   // non-empty = unsupported
};

bool pth_inspect(const std::string& pth, PthInfo* out, std::string* err);

// template_dir: template.params / template.config / template.cfg.  Writes out_dir/weights.bin, out_dir/engine.cfg.
bool pth_import(const std::string& pth, const std::string& template_dir, const std::string& out_dir, int sid,
                std::string* err);

// Writes out_onnx = a copy of template_onnx whose initializers named in weights_bin hold that voice's weights
// (same shape/size, so the patch is done in place on the raw_data bytes). The result is an ordinary ONNX file
// with real weights, equivalent to the PC pack's synth.onnx, so no runtime weight override is needed.
bool pth_bake(const std::string& template_onnx, const std::string& weights_bin, const std::string& out_onnx,
              std::string* err);

}  // namespace rvc
