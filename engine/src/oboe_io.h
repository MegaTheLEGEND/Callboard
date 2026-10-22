// Low-latency full-duplex audio (mono, float, 48 kHz) on top of Oboe/AAudio.
// Android only. Feeds / drains a VoiceSession from the real-time audio callbacks.
#pragma once
#include <atomic>
#include <cstring>
#include <memory>
#include <string>

#include <oboe/Oboe.h>

#include "voice_session.h"

namespace rvc {

class OboeIO {
 public:
  explicit OboeIO(VoiceSession* session) : session_(session) {}
  ~OboeIO() { stop(); }

  // device ids: 0 = system default (oboe::kUnspecified)
  bool start(int input_device, int output_device, bool unprocessed_mic, bool with_output, std::string* err);
  void stop();

  // measured by AAudio when available; < 0 if unknown
  float input_latency_ms() const { return in_latency_ms_; }
  float output_latency_ms() const { return out_latency_ms_; }
  bool exclusive() const { return exclusive_in_ && exclusive_out_; }
  // true after a headset unplug / route change killed a stream; the app should restart audio
  static bool disconnected();

 private:
  struct InCb;
  struct OutCb;
  VoiceSession* session_;
  std::shared_ptr<oboe::AudioStream> in_, out_;
  std::shared_ptr<InCb> in_cb_;
  std::shared_ptr<OutCb> out_cb_;
  float in_latency_ms_ = -1, out_latency_ms_ = -1;
  bool exclusive_in_ = false, exclusive_out_ = false;
};

}  // namespace rvc
