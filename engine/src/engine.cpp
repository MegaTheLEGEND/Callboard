#include "engine.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <future>
#include <map>
#include <numeric>
#include <sstream>

namespace rvc {

using Clock = std::chrono::steady_clock;
static inline float ms_since(Clock::time_point t) {
  return std::chrono::duration<float, std::milli>(Clock::now() - t).count();
}

// ------------------------------------------------------------------ pack config
bool PackConfig::load(const std::string& path, PackConfig* o, std::string* err) {
  std::ifstream f(path);
  if (!f) { if (err) *err = "cannot open " + path; return false; }
  std::map<std::string, double> kv;
  std::string line;
  while (std::getline(f, line)) {
    std::istringstream ss(line);
    std::string k; double v;
    if (ss >> k >> v) kv[k] = v;
  }
  auto I = [&](const char* k, int def) { auto it = kv.find(k); return it == kv.end() ? def : int(it->second); };
  auto F = [&](const char* k, float def) { auto it = kv.find(k); return it == kv.end() ? def : float(it->second); };
  const char* required[] = {"window", "skip_head", "return_length", "hubert_samples", "hubert_frames",
                            "fcpe_samples", "fcpe_frames", "tgt_sr", "upp", "feat_dim", "out_samples"};
  for (auto k : required)
    if (!kv.count(k)) { if (err) *err = std::string("engine.cfg missing key: ") + k; return false; }
  o->block = I("block", 10); o->extra = I("extra", 50); o->crossfade = I("crossfade", 5);
  o->sola_search = I("sola_search", 1); o->tgt_sr = I("tgt_sr", 40000); o->upp = I("upp", 400);
  o->feat_dim = I("feat_dim", 768); o->inter_channels = I("inter_channels", 192);
  o->window = I("window", 66); o->sola_buffer = I("sola_buffer", 4); o->skip_head = I("skip_head", 50);
  o->return_length = I("return_length", 15); o->flow_head = I("flow_head", 26); o->dec_head = I("dec_head", 24);
  o->hubert_samples = I("hubert_samples", 0); o->hubert_frames = I("hubert_frames", 0);
  o->fcpe_samples = I("fcpe_samples", 0); o->fcpe_frames = I("fcpe_frames", 0);
  o->fcpe_bins = I("fcpe_bins", 360); o->fcpe_half_window = I("fcpe_half_window", 4);
  o->fcpe_threshold = F("fcpe_threshold", 0.006f); o->out_samples = I("out_samples", 0);
  o->source_lin_w = F("source_lin_w", 1.f); o->source_lin_b = F("source_lin_b", 0.f);
  o->has_index = I("has_index", 0) != 0;
  if (o->sola_buffer * 10 > o->block * 10 * 4) { if (err) *err = "bad sola geometry"; return false; }
  return true;
}

// Cache tag for ONE graph: its sha256 from manifest.json (+ ORT version), so graphs shared between
// voices keep their compiled NPU context and only a new voice's synth is recompiled.
static std::string graph_tag(const std::string& dir, const std::string& file, const std::string& fallback) {
  std::ifstream f(dir + "/manifest.json", std::ios::binary);
  std::stringstream ss; ss << f.rdbuf();
  const std::string m = ss.str();
  size_t p = m.find("\"" + file + "\"");
  if (p == std::string::npos) return fallback;
  p = m.find("\"sha256\"", p);
  if (p == std::string::npos) return fallback;
  p = m.find('"', m.find(':', p) + 1);
  const size_t e = m.find('"', p + 1);
  if (p == std::string::npos || e == std::string::npos) return fallback;
  std::string key = m.substr(p + 1, e - p - 1) + OrtGetApiBase()->GetVersionString();
  uint64_t h = 1469598103934665603ull;
  for (unsigned char ch : key) { h ^= ch; h *= 1099511628211ull; }
  char buf[20];
  snprintf(buf, sizeof(buf), "%016llx", (unsigned long long)h);
  return buf;
}

Engine::Engine() = default;
Engine::~Engine() = default;

// ------------------------------------------------------------------------ init
bool Engine::init(const std::string& dir, const BackendConfig& cfg, std::string* err) {
  ready_ = false;
  cfg_ = cfg;
  if (!PackConfig::load(dir + "/engine.cfg", &pc_, err)) return false;
  const auto& c = pc_;
  const int D = c.feat_dim;

  {  // FCPE decode table
    std::ifstream f(dir + "/fcpe_cents.f32", std::ios::binary);
    cents_.resize(c.fcpe_bins);
    if (!f.read(reinterpret_cast<char*>(cents_.data()), std::streamsize(cents_.size() * 4))) {
      if (err) *err = "cannot read fcpe_cents.f32";
      return false;
    }
  }

  // ---- buffers -------------------------------------------------------------
  const int block48 = block_samples();
  tmp48_.assign(block48, 0.f);
  tmp16_.assign(size_t(block48) / 3 + 8, 0.f);
  in16_.assign(c.hubert_samples, 0.f);
  mel_.assign(size_t(c.fcpe_frames) * 128, 0.f);
  latent_.assign(size_t(c.fcpe_frames) * c.fcpe_bins, 0.f);
  f0raw_.assign(c.fcpe_frames, 0.f);
  coarse_raw_.assign(c.fcpe_frames, 0);
  feats_.assign(size_t(c.hubert_frames + 1) * D, 0.f);
  pitch_.assign(c.window, 1);
  pitchf_.assign(c.window, 0.f);
  har_.assign(c.out_samples, 0.f);
  sine_noise_.assign(c.out_samples, 0.f);
  const int nz_frames = c.window - c.flow_head;
  noise_.assign(size_t(c.inter_channels) * nz_frames, 0.f);
  audio_.assign(c.out_samples, 0.f);
  wav48_.assign(size_t(c.return_length) * kDeviceZc, 0.f);

  // ---- DSP state -----------------------------------------------------------
  mic_rs_ = std::make_unique<StreamResampler>(ResamplerKernel::design(1, kDeviceSr / 16000));
  const int g = std::gcd(c.tgt_sr, kDeviceSr);
  out_kernel_ = ResamplerKernel::design(kDeviceSr / g, c.tgt_sr / g);
  if (size_t(c.out_samples) * (kDeviceSr / g) / (c.tgt_sr / g) != wav48_.size()) {
    if (err) *err = "resampled synth output does not match return_length*zc";
    return false;
  }
  sola_ = std::make_unique<Sola>(block48, c.sola_buffer * kDeviceZc, c.sola_search * kDeviceZc);

  // ---- retrieval -----------------------------------------------------------
  if (c.has_index) {
    std::string e;
    if (!index_.load(dir + "/index.bin", &e)) {
      if (err) *err = "index: " + e;
      return false;
    }
    if (index_.dim() != D) { if (err) *err = "index dim != feature dim"; return false; }
  }

  // ---- graphs --------------------------------------------------------------
  using T = ONNXTensorElementDataType;
  const T F32 = ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, I64 = ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64;
  const int64_t S = c.fcpe_samples, FR = c.fcpe_frames, H = c.hubert_samples;

  if (!fcpe_mel_.init(env_, dir + "/fcpe_mel.onnx", "fcpe_mel", {{"audio", {1, S}, F32}},
                      {{"mel", {1, FR, 128}, F32}}, cfg, /*on_npu=*/false, err)) return false;
  auto tagged = [&](const char* file) { BackendConfig b = cfg; b.cache_tag = graph_tag(dir, file, cfg.cache_tag); return b; };
  if (!fcpe_net_.init(env_, dir + "/fcpe_net.onnx", "fcpe_net", {{"mel", {1, FR, 128}, F32}},
                      {{"latent", {1, FR, c.fcpe_bins}, F32}}, tagged("fcpe_net.onnx"), true, err)) return false;
  if (!hubert_.init(env_, dir + "/hubert.onnx", "hubert", {{"audio", {1, H}, F32}},
                    {{"feats", {1, c.hubert_frames, D}, F32}}, tagged("hubert.onnx"), true, err)) return false;
  if (std::ifstream(dir + "/weights.bin").good()) synth_.set_weights(dir + "/weights.bin");
  if (!synth_.init(env_, dir + "/synth.onnx", "synth",
                   {{"feats", {1, c.hubert_frames + 1, D}, F32},
                    {"pitch", {1, c.window}, I64},
                    {"har", {1, 1, c.out_samples}, F32},
                    {"noise", {1, c.inter_channels, nz_frames}, F32}},
                   {{"audio", {1, 1, c.out_samples}, F32}}, tagged("synth.onnx"), true, err)) return false;

  // zero-copy wiring: FCPE reads the newest samples of the 16k window in place;
  // HuBERT writes straight into the synth's feature input.
  fcpe_mel_.bind_input(0, in16_.data() + (H - S));
  fcpe_mel_.bind_output(0, mel_.data());
  fcpe_net_.bind_input(0, mel_.data());
  fcpe_net_.bind_output(0, latent_.data());
  hubert_.bind_input(0, in16_.data());
  hubert_.bind_output(0, feats_.data());
  synth_.bind_input(0, feats_.data());
  synth_.bind_input(1, pitch_.data());
  synth_.bind_input(2, har_.data());
  synth_.bind_input(3, noise_.data());
  synth_.bind_output(0, audio_.data());

  ready_ = true;
  // warm-up: first HTP executions are slow (VTCM/graph init); keep that out of the stream
  std::vector<float> z(block48, 0.f), o(block48);
  for (int i = 0; i < 3; ++i) process_block(z.data(), o.data());
  reset();
  return true;
}

void Engine::reset() {
  std::fill(in16_.begin(), in16_.end(), 0.f);
  mic_rs_->reset();
  sola_->reset();
  pitch_cache_.clear();
  raw_f0_.clear();
  iso_active_ = false;  // next process_block() re-primes the isolator
  stab_active_ = false;
}

bool Engine::npu_cached(const char* tag) const {
  const std::string t = tag;
  if (t == "hubert") return hubert_.used_cached_context();
  if (t == "synth") return synth_.used_cached_context();
  if (t == "fcpe_net") return fcpe_net_.used_cached_context();
  return false;
}

void Engine::run_fcpe() {
  fcpe_mel_.run();  // CPU, FP32
  fcpe_net_.run();  // HTP
}

// ----------------------------------------------------------------- per block
void Engine::process_block(const float* in48, float* out48) {
  if (!ready_) { std::memset(out48, 0, sizeof(float) * block_samples()); return; }
  const auto& c = pc_;
  const int D = c.feat_dim;
  const int block48 = block_samples();
  auto t_start = Clock::now(), t = t_start;

  // 1. gate, 48k -> 16k, slide the 16k window --------------------------------
  std::memcpy(tmp48_.data(), in48, sizeof(float) * block48);
  const float gate = params.gate_db.load(std::memory_order_relaxed);
  if (gate > -60.f) noise_gate(tmp48_.data(), block48, kDeviceZc, gate);
  const size_t n16 = std::min(mic_rs_->process(tmp48_.data(), block48, tmp16_.data()), in16_.size());
  {  // voice-only noise suppression (adds VoiceIsolator::latency_samples() = 20 ms while on)
    const float iso = params.isolate.load(std::memory_order_relaxed);
    if (iso > 0.f) {
      if (!iso_active_) { isolator_.reset(); iso_active_ = true; }
      isolator_.set_strength(iso);
      isolator_.process(tmp16_.data(), n16);
    } else {
      iso_active_ = false;
    }
  }
  std::memmove(in16_.data(), in16_.data() + n16, sizeof(float) * (in16_.size() - n16));
  std::memcpy(in16_.data() + in16_.size() - n16, tmp16_.data(), sizeof(float) * n16);
  last_.pre = ms_since(t);

  // 2. pitch (FCPE) in parallel with HuBERT ------------------------------------
  t = Clock::now();
  auto fcpe_done = std::async(std::launch::async, [this] { run_fcpe(); });
  hubert_.run();
  last_.hubert = ms_since(t);
  // RVC appends a copy of the last frame before the x2 upsample
  std::memcpy(feats_.data() + size_t(c.hubert_frames) * D,
              feats_.data() + size_t(c.hubert_frames - 1) * D, sizeof(float) * D);
  fcpe_done.get();
  last_.pitch = ms_since(t);  // wall time of the slower of the two

  // pitch post-processing -> rolling cache -> window-aligned tails
  t = Clock::now();
  fcpe_decode(latent_.data(), c.fcpe_frames, c.fcpe_bins, cents_.data(), c.fcpe_threshold,
              c.fcpe_half_window, f0raw_.data());
  const float pstab = params.pitch_stab.load(std::memory_order_relaxed);
  if (pstab > 0.f && c.fcpe_frames >= 12) {
    if (!stab_active_) { raw_f0_.clear(); stab_active_ = true; }
    raw_f0_.shift(int(n16 / 160));
    float hist[24];
    raw_f0_.tail_f0(24, hist);  // hist[0..9) = the frames that precede this block's frame 0
    stabilize_f0(f0raw_.data(), c.fcpe_frames, hist, 9, pstab);
    raw_f0_.write_tail(nullptr, f0raw_.data() + 3, c.fcpe_frames - 4);
  } else {
    stab_active_ = false;
  }
  interp_unvoiced(f0raw_.data(), c.fcpe_frames);
  const float key = std::exp2(params.pitch_semitones.load(std::memory_order_relaxed) / 12.f);
  for (int i = 0; i < c.fcpe_frames; ++i) f0raw_[i] *= key;
  f0_to_coarse(f0raw_.data(), c.fcpe_frames, coarse_raw_.data());
  pitch_cache_.update(coarse_raw_.data(), f0raw_.data(), c.fcpe_frames, int(n16 / 160));
  pitch_cache_.tail(c.window, pitch_.data(), pitchf_.data());

  // 3. retrieval: only the frames that survive skip_head are blended -----------
  const float rate = params.index_rate.load(std::memory_order_relaxed);
  bool index_used = false;
  if (c.has_index && rate > 0.f) {
    const int first = c.skip_head / 2, total = c.hubert_frames + 1;
    if (total > first) {
      float* f = feats_.data() + size_t(first) * D;
      const size_t cnt = size_t(total - first) * size_t(D);
      if (blend_pre_.size() < cnt) blend_pre_.resize(cnt);
      std::memcpy(blend_pre_.data(), f, cnt * sizeof(float));
      const auto tb = Clock::now();
      index_.blend(f, total - first, rate, 2);
      index_ms_.store(ms_since(tb), std::memory_order_relaxed);
      double num = 0, den = 0;  // how far the retrieved neighbours moved the features
      for (size_t i = 0; i < cnt; ++i) { const double d = double(f[i]) - double(blend_pre_[i]); num += d * d; den += double(blend_pre_[i]) * blend_pre_[i]; }
      index_shift_.store(float(std::sqrt(num / (den + 1e-12))), std::memory_order_relaxed);
      index_used = true;
    }
  }
  index_ran_.store(index_used, std::memory_order_relaxed);
  last_.retrieval = ms_since(t);

  // 4. excitation + noise on the CPU, then the vocoder on the NPU ---------------
  const bool zn = params.synth_noise.load(std::memory_order_relaxed);
  if (zn) {
    rng_.fill_normal(noise_.data(), noise_.size());
    rng_.fill_normal(sine_noise_.data(), sine_noise_.size());
  } else {
    std::fill(noise_.begin(), noise_.end(), 0.f);
  }
  sine_excitation(pitchf_.data() + c.skip_head, c.return_length, c.tgt_sr, c.upp, c.source_lin_w,
                  c.source_lin_b, zn ? sine_noise_.data() : nullptr, har_.data());
  t = Clock::now();
  synth_.run();
  last_.synth = ms_since(t);

  // 5. back to 48 kHz and stitch with SOLA --------------------------------------
  t = Clock::now();
  resample_block(out_kernel_, audio_.data(), audio_.size(), wav48_.data(), wav48_.size());
  sola_->process(wav48_.data(), out48);
  last_.post = ms_since(t);
  last_.total = ms_since(t_start);
}

}  // namespace rvc
