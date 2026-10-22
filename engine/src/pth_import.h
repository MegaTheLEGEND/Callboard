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

}  // namespace rvc
