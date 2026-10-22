// Desktop harness: pushes a 48 kHz mono 16-bit WAV through the *same* Engine the
// phone runs, block by block, and reports per-stage timing.
//
//   offline_run --pack pack/ --in in.wav --out out.wav [--backend cpu|qnn]
//               [--pitch 0] [--index-rate 0.5] [--no-noise] [--threads 4]
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../src/engine.h"

static bool read_wav(const std::string& path, std::vector<float>* pcm, int* sr) {
  FILE* f = std::fopen(path.c_str(), "rb");
  if (!f) return false;
  char hdr[12];
  if (std::fread(hdr, 1, 12, f) != 12 || std::memcmp(hdr, "RIFF", 4) || std::memcmp(hdr + 8, "WAVE", 4)) return false;
  int ch = 1, bits = 16;
  while (true) {
    char id[4]; uint32_t sz;
    if (std::fread(id, 1, 4, f) != 4 || std::fread(&sz, 4, 1, f) != 1) return false;
    if (!std::memcmp(id, "fmt ", 4)) {
      uint16_t fmt, c, b; uint32_t s, br; uint16_t ba;
      std::fread(&fmt, 2, 1, f); std::fread(&c, 2, 1, f); std::fread(&s, 4, 1, f);
      std::fread(&br, 4, 1, f); std::fread(&ba, 2, 1, f); std::fread(&b, 2, 1, f);
      std::fseek(f, long(sz) - 16, SEEK_CUR);
      ch = c; bits = b; *sr = int(s);
    } else if (!std::memcmp(id, "data", 4)) {
      if (bits != 16) return false;
      std::vector<int16_t> raw(sz / 2);
      if (std::fread(raw.data(), 2, raw.size(), f) != raw.size()) return false;
      pcm->resize(raw.size() / ch);
      for (size_t i = 0; i < pcm->size(); ++i) (*pcm)[i] = raw[i * ch] / 32768.f;
      std::fclose(f);
      return true;
    } else {
      std::fseek(f, long(sz), SEEK_CUR);
    }
  }
}

static void write_wav(const std::string& path, const std::vector<float>& pcm, int sr) {
  FILE* f = std::fopen(path.c_str(), "wb");
  const uint32_t data = uint32_t(pcm.size() * 2), riff = 36 + data, br = uint32_t(sr * 2);
  const uint16_t fmt = 1, ch = 1, ba = 2, bits = 16;
  const uint32_t sixteen = 16, srr = uint32_t(sr);
  std::fwrite("RIFF", 1, 4, f); std::fwrite(&riff, 4, 1, f); std::fwrite("WAVEfmt ", 1, 8, f);
  std::fwrite(&sixteen, 4, 1, f); std::fwrite(&fmt, 2, 1, f); std::fwrite(&ch, 2, 1, f);
  std::fwrite(&srr, 4, 1, f); std::fwrite(&br, 4, 1, f); std::fwrite(&ba, 2, 1, f);
  std::fwrite(&bits, 2, 1, f); std::fwrite("data", 1, 4, f); std::fwrite(&data, 4, 1, f);
  for (float x : pcm) {
    const float c = std::max(-1.f, std::min(1.f, x));
    const int16_t s = int16_t(std::lrintf(c * 32767.f));
    std::fwrite(&s, 2, 1, f);
  }
  std::fclose(f);
}

int main(int argc, char** argv) {
  std::string pack, in, out = "out.wav", backend = "cpu";
  float pitch = 0.f, rate = 0.5f;
  bool noise = true;
  int threads = 4;
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    auto next = [&] { return std::string(argv[++i]); };
    if (a == "--pack") pack = next();
    else if (a == "--in") in = next();
    else if (a == "--out") out = next();
    else if (a == "--backend") backend = next();
    else if (a == "--pitch") pitch = std::stof(next());
    else if (a == "--index-rate") rate = std::stof(next());
    else if (a == "--threads") threads = std::stoi(next());
    else if (a == "--no-noise") noise = false;
  }
  if (pack.empty() || in.empty()) { std::puts("usage: offline_run --pack DIR --in IN.wav [--out OUT.wav]"); return 2; }

  std::vector<float> pcm;
  int sr = 0;
  if (!read_wav(in, &pcm, &sr) || sr != 48000) { std::puts("input must be 16-bit PCM WAV at 48000 Hz"); return 2; }

  rvc::BackendConfig cfg;
  cfg.kind = backend == "qnn" ? rvc::BackendConfig::Kind::QnnHtp : rvc::BackendConfig::Kind::Cpu;
  cfg.cpu_threads = threads;
  cfg.cache_dir = pack;
  rvc::Engine eng;
  std::string err;
  auto t0 = std::chrono::steady_clock::now();
  if (!eng.init(pack, cfg, &err)) { std::printf("init failed: %s\n", err.c_str()); return 1; }
  std::printf("engine ready in %.1f s (backend=%s, block=%d ms, latency floor %.0f ms)\n",
              std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(), backend.c_str(),
              eng.pack().block * 10, eng.latency_floor_ms());
  eng.params.pitch_semitones = pitch;
  eng.params.index_rate = rate;
  eng.params.synth_noise = noise;

  const int B = eng.block_samples();
  const size_t blocks = pcm.size() / B;
  std::vector<float> outpcm(blocks * B), blk(B);
  std::vector<double> tot, hub, syn, ret, pit;
  for (size_t b = 0; b < blocks; ++b) {
    eng.process_block(pcm.data() + b * B, outpcm.data() + b * B);
    auto s = eng.last();
    tot.push_back(s.total); hub.push_back(s.hubert); syn.push_back(s.synth);
    ret.push_back(s.retrieval); pit.push_back(s.pitch);
  }
  write_wav(out, outpcm, 48000);

  auto stat = [](std::vector<double> v, const char* n) {
    std::sort(v.begin(), v.end());
    double m = 0; for (double x : v) m += x; m /= v.size();
    std::printf("  %-10s mean %6.1f  p95 %6.1f  max %6.1f ms\n", n, m, v[size_t(v.size() * 0.95)], v.back());
  };
  std::printf("%zu blocks (%.1f s audio)\n", blocks, blocks * B / 48000.0);
  stat(tot, "total"); stat(pit, "hubert||f0"); stat(ret, "retrieval+"); stat(syn, "synth");
  std::sort(tot.begin(), tot.end());
  const double budget = eng.pack().block * 10.0;
  std::printf("real-time factor (mean): %.2f   blocks over %.0f ms budget: %d\n",
              [&] { double m = 0; for (double x : tot) m += x; return m / tot.size() / budget; }(), budget,
              int(std::count_if(tot.begin(), tot.end(), [&](double x) { return x > budget; })));
  double rms = 0, pk = 0;
  for (float x : outpcm) { rms += double(x) * x; pk = std::max(pk, double(std::fabs(x))); }
  std::printf("output rms %.4f peak %.4f\n", std::sqrt(rms / outpcm.size()), pk);
  return 0;
}
