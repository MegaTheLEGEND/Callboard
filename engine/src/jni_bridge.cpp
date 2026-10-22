// JNI surface for dev.callboard.rvc.NativeBridge. One engine + one session per process.
//
// Three ways the engine is driven:
//   * standalone test   nativeStart()      Oboe mic -> engine -> Oboe speaker (headphones)
//   * live call         nativeStartLive()  mic frames pushed in from Kotlin (shell or app mic),
//                       nativePullUplink() 20 ms of converted voice pulled out at 8/16 kHz for the call uplink
//   * offline           nativeConvertBegin()/nativeConvertBlock()  run a decoded clip through the voice
#include <android/log.h>
#include <jni.h>
#include <stdlib.h>

#include <algorithm>
#include <cmath>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "engine.h"
#include "index_import.h"
#include "oboe_io.h"
#include "pth_import.h"
#include "voice_session.h"

#define LOG_TAG "rvc-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct App {
  std::unique_ptr<rvc::Engine> engine;
  std::unique_ptr<rvc::VoiceSession> session;
  std::unique_ptr<rvc::OboeIO> io;
  std::mutex mu;
  // live-call uplink conversion (48 kHz engine output -> 8/16 kHz call audio)
  std::unique_ptr<rvc::StreamResampler> up_rs;
  int up_rate = 8000;
  std::vector<float> tmp48, tmp_out;
  std::vector<jshort> tmp_s;
} g;

std::string to_str(JNIEnv* env, jstring s) {
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r = c ? c : "";
  if (c) env->ReleaseStringUTFChars(s, c);
  return r;
}

// Identifies "this pack on this ORT build" so stale HTP context binaries are never reused.
std::string cache_tag(const std::string& pack) {
  std::ifstream f(pack + "/manifest.json", std::ios::binary);
  std::stringstream ss;
  ss << f.rdbuf();
  ss << OrtGetApiBase()->GetVersionString();
  uint64_t h = 1469598103934665603ull;  // FNV-1a
  for (unsigned char ch : ss.str()) { h ^= ch; h *= 1099511628211ull; }
  char buf[20];
  snprintf(buf, sizeof(buf), "%016llx", (unsigned long long)h);
  return buf;
}

inline jshort to_s16(float x) {
  x = std::max(-1.f, std::min(1.f, x));
  return jshort(std::lrintf(x * 32767.f));
}

}  // namespace

extern "C" {

// Returns "" on success, otherwise an error message. Slow (seconds; minutes on the very first
// run while the HTP graphs are compiled and cached) -> call from a background thread.
JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeCreate(
    JNIEnv* env, jobject, jstring jpack, jstring jctx, jstring jlibs, jstring jperf,
    jboolean strict, jboolean cpu, jboolean verbose) {
  std::lock_guard<std::mutex> lk(g.mu);
  g.io.reset(); g.session.reset(); g.engine.reset();
  const std::string pack = to_str(env, jpack), ctx = to_str(env, jctx), libs = to_str(env, jlibs);

  // The Hexagon DSP loads its skel library by path; without this the HTP backend fails to open.
  setenv("ADSP_LIBRARY_PATH", (libs + ";/vendor/lib64/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp").c_str(), 1);
  setenv("LD_LIBRARY_PATH", (libs + ":/vendor/lib64:/system/lib64").c_str(), 1);

  rvc::BackendConfig cfg;
  cfg.kind = cpu ? rvc::BackendConfig::Kind::Cpu : rvc::BackendConfig::Kind::QnnHtp;
  cfg.cache_dir = ctx;
  cfg.htp_perf_mode = to_str(env, jperf);
  cfg.strict_npu = strict;
  cfg.verbose = verbose;
  cfg.cpu_threads = 2;
  cfg.cache_tag = cache_tag(pack);

  auto eng = std::make_unique<rvc::Engine>();
  std::string err;
  if (!eng->init(pack, cfg, &err)) {
    LOGE("engine init failed: %s", err.c_str());
    return env->NewStringUTF(err.c_str());
  }
  rvc::Engine* e = eng.get();
  g.engine = std::move(eng);
  g.session = std::make_unique<rvc::VoiceSession>(
      e->block_samples(), rvc::Engine::kDeviceSr,
      [e](const float* in, float* out) { e->process_block(in, out); });
  g.io = std::make_unique<rvc::OboeIO>(g.session.get());
  LOGI("engine ready: block=%d samples, floor=%.0f ms", e->block_samples(), e->latency_floor_ms());
  return env->NewStringUTF("");
}

// "ok|version|sr|speakers|f0|reason" or "err|message".  Pure file parsing; safe to call any time.
JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeInspectPth(JNIEnv* env, jobject, jstring jpth) {
  rvc::PthInfo i; std::string e;
  if (!rvc::pth_inspect(to_str(env, jpth), &i, &e)) return env->NewStringUTF(("err|" + e).c_str());
  return env->NewStringUTF(("ok|" + i.version + "|" + std::to_string(i.sr) + "|" + std::to_string(i.speakers) + "|" +
                            std::to_string(i.f0) + "|" + i.reason).c_str());
}

// Writes weights.bin + engine.cfg into outDir. "" = ok.
JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeImportPth(
    JNIEnv* env, jobject, jstring jpth, jstring jtpl, jstring jout, jint sid) {
  std::string e;
  if (!rvc::pth_import(to_str(env, jpth), to_str(env, jtpl), to_str(env, jout), sid, &e)) return env->NewStringUTF(e.c_str());
  return env->NewStringUTF("");
}

// Copies the template synth.onnx to outOnnx with this voice's weights written into its initializers. "" = ok.
JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeBakeSynth(
    JNIEnv* env, jobject, jstring jtpl, jstring jweights, jstring jout) {
  std::string e;
  if (!rvc::pth_bake(to_str(env, jtpl), to_str(env, jweights), to_str(env, jout), &e)) return env->NewStringUTF(e.c_str());
  return env->NewStringUTF("");
}

// faiss .index -> index.bin. "ok|vectors|dim" or "err|message".
JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeImportIndex(
    JNIEnv* env, jobject, jstring jin, jstring jout, jint dim) {
  int n = 0, d = 0; std::string e;
  if (!rvc::index_import(to_str(env, jin), to_str(env, jout), dim, &n, &d, &e)) return env->NewStringUTF(("err|" + e).c_str());
  return env->NewStringUTF(("ok|" + std::to_string(n) + "|" + std::to_string(d)).c_str());
}

// [0] samples per engine block at 48 kHz   [1] output lookahead in samples (offline alignment)
JNIEXPORT jintArray JNICALL Java_dev_callboard_rvc_NativeBridge_nativeInfo(JNIEnv* env, jobject) {
  jint v[2] = {0, 0};
  if (g.engine) {
    v[0] = g.engine->block_samples();
    v[1] = (g.engine->pack().sola_buffer + g.engine->pack().sola_search) * rvc::Engine::kDeviceZc;
  }
  jintArray out = env->NewIntArray(2);
  env->SetIntArrayRegion(out, 0, 2, v);
  return out;
}

// ---------------------------------------------------------------- standalone test
JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeStart(
    JNIEnv* env, jobject, jint in_dev, jint out_dev, jint slack_ms, jboolean unprocessed) {
  std::lock_guard<std::mutex> lk(g.mu);
  if (!g.engine || !g.session) return env->NewStringUTF("engine not loaded");
  g.session->stop();
  g.engine->reset();
  g.session->configure(float(slack_ms));
  g.session->start();
  std::string err;
  if (!g.io->start(in_dev, out_dev, unprocessed, /*with_output=*/true, &err)) {
    g.session->stop();
    return env->NewStringUTF(err.c_str());
  }
  return env->NewStringUTF("");
}

// ------------------------------------------------------------------- live call
// Starts the inference thread only. Mic frames arrive through nativePushMic (shell mic) or
// nativeStartAppMic (Oboe capture); converted audio leaves through nativePullUplink.
JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeStartLive(
    JNIEnv* env, jobject, jint slack_ms, jint uplink_rate) {
  std::lock_guard<std::mutex> lk(g.mu);
  if (!g.engine || !g.session) return env->NewStringUTF("engine not loaded");
  if (uplink_rate != 8000 && uplink_rate != 16000) return env->NewStringUTF("uplink rate must be 8000 or 16000");
  g.session->stop();
  if (g.io) g.io->stop();
  g.engine->reset();
  g.up_rate = uplink_rate;
  g.up_rs = std::make_unique<rvc::StreamResampler>(
      rvc::ResamplerKernel::design(1, rvc::Engine::kDeviceSr / uplink_rate));
  g.session->configure(float(slack_ms));
  g.session->start();
  return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL Java_dev_callboard_rvc_NativeBridge_nativeStartAppMic(
    JNIEnv* env, jobject, jint in_dev, jboolean unprocessed) {
  std::lock_guard<std::mutex> lk(g.mu);
  if (!g.io || !g.session || !g.session->running()) return env->NewStringUTF("live session not started");
  std::string err;
  if (!g.io->start(in_dev, 0, unprocessed, /*with_output=*/false, &err)) return env->NewStringUTF(err.c_str());
  return env->NewStringUTF("");
}

// pcm: n mono PCM16 samples at 48 kHz (the shell helper's mic capture). Single producer thread.
JNIEXPORT void JNICALL Java_dev_callboard_rvc_NativeBridge_nativePushMic(
    JNIEnv* env, jobject, jshortArray pcm, jint n) {
  if (!g.session || n <= 0) return;
  static thread_local std::vector<jshort> s;
  static thread_local std::vector<float> f;
  s.resize(size_t(n));
  f.resize(size_t(n));
  env->GetShortArrayRegion(pcm, 0, n, s.data());
  for (jint i = 0; i < n; ++i) f[size_t(i)] = float(s[size_t(i)]) * (1.f / 32768.f);
  g.session->on_input(f.data(), n);
}

// Fills `out` with n PCM16 samples at the uplink rate (zeros while the engine has nothing yet).
// Single consumer thread; call every 20 ms (n = rate / 50).
JNIEXPORT jint JNICALL Java_dev_callboard_rvc_NativeBridge_nativePullUplink(
    JNIEnv* env, jobject, jshortArray out, jint n) {
  if (!g.session || !g.up_rs || n <= 0) return 0;
  const int M = rvc::Engine::kDeviceSr / g.up_rate;
  const size_t n48 = size_t(n) * size_t(M);
  g.tmp48.resize(n48);
  g.tmp_out.resize(g.up_rs->max_out(n48));
  g.session->on_output(g.tmp48.data(), int(n48));
  const size_t got = g.up_rs->process(g.tmp48.data(), n48, g.tmp_out.data());
  g.tmp_s.assign(size_t(n), 0);
  for (size_t i = 0; i < std::min(got, size_t(n)); ++i) g.tmp_s[i] = to_s16(g.tmp_out[i]);
  env->SetShortArrayRegion(out, 0, n, g.tmp_s.data());
  return jint(std::min(got, size_t(n)));
}

JNIEXPORT void JNICALL Java_dev_callboard_rvc_NativeBridge_nativeStop(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lk(g.mu);
  if (g.io) g.io->stop();
  if (g.session) g.session->stop();
}

JNIEXPORT void JNICALL Java_dev_callboard_rvc_NativeBridge_nativeRelease(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lk(g.mu);
  g.io.reset(); g.session.reset(); g.engine.reset(); g.up_rs.reset();
}

// ----------------------------------------------------------------------- offline
// Returns the block size in samples (48 kHz), or -1 if the engine is missing or a live/test
// session is running. Resets the streaming state so the clip starts clean.
JNIEXPORT jint JNICALL Java_dev_callboard_rvc_NativeBridge_nativeConvertBegin(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lk(g.mu);
  if (!g.engine || !g.session || g.session->running()) return -1;
  g.engine->reset();
  return g.engine->block_samples();
}

// in/out: exactly one block of mono PCM16 at 48 kHz.
JNIEXPORT jboolean JNICALL Java_dev_callboard_rvc_NativeBridge_nativeConvertBlock(
    JNIEnv* env, jobject, jshortArray in, jshortArray out) {
  std::lock_guard<std::mutex> lk(g.mu);
  if (!g.engine || !g.session || g.session->running()) return JNI_FALSE;
  const int B = g.engine->block_samples();
  std::vector<jshort> s(static_cast<size_t>(B));
  std::vector<float> fi(static_cast<size_t>(B));
  std::vector<float> fo(static_cast<size_t>(B));
  env->GetShortArrayRegion(in, 0, B, s.data());
  for (int i = 0; i < B; ++i) fi[size_t(i)] = float(s[size_t(i)]) * (1.f / 32768.f);
  g.engine->process_block(fi.data(), fo.data());
  for (int i = 0; i < B; ++i) s[size_t(i)] = to_s16(fo[size_t(i)]);
  env->SetShortArrayRegion(out, 0, B, s.data());
  return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_dev_callboard_rvc_NativeBridge_nativeSetParams(
    JNIEnv*, jobject, jfloat pitch, jfloat index_rate, jfloat gate_db, jboolean synth_noise) {
  if (!g.engine) return;  // atomics: no lock needed, never blocks the audio path
  g.engine->params.pitch_semitones = pitch;
  g.engine->params.index_rate = index_rate;
  g.engine->params.gate_db = gate_db;
  g.engine->params.synth_noise = synth_noise;
}

// [0]total [1]pitch||hubert [2]hubert [3]retrieval+pitchpost [4]synth [5]post
// [6]underruns [7]dropped [8]slack [9]out_level [10]in_level [11]latency floor [12]block ms
// [13]audio in lat [14]audio out lat [15]exclusive [16]disconnected [17]ctx cached(all NPU graphs)
// [18]mic peak 0..1 since last call [19]session running
JNIEXPORT jfloatArray JNICALL Java_dev_callboard_rvc_NativeBridge_nativeStats(JNIEnv* env, jobject) {
  float v[20] = {0};
  if (g.engine && g.session) {
    auto t = g.engine->last();
    auto s = g.session->stats();
    v[0] = t.total; v[1] = t.pitch; v[2] = t.hubert; v[3] = t.retrieval; v[4] = t.synth; v[5] = t.post;
    v[6] = float(s.underruns); v[7] = float(s.dropped_blocks); v[8] = s.slack_ms;
    v[9] = s.out_level_ms; v[10] = s.in_level_ms;
    v[11] = g.engine->latency_floor_ms(); v[12] = g.engine->pack().block * 10.f;
    if (g.io) {
      v[13] = g.io->input_latency_ms(); v[14] = g.io->output_latency_ms();
      v[15] = g.io->exclusive() ? 1.f : 0.f;
    }
    v[16] = rvc::OboeIO::disconnected() ? 1.f : 0.f;
    v[17] = (g.engine->npu_cached("hubert") && g.engine->npu_cached("synth") &&
             g.engine->npu_cached("fcpe_net")) ? 1.f : 0.f;
    v[18] = s.in_peak;
    v[19] = g.session->running() ? 1.f : 0.f;
  }
  jfloatArray out = env->NewFloatArray(20);
  env->SetFloatArrayRegion(out, 0, 20, v);
  return out;
}

}  // extern "C"
