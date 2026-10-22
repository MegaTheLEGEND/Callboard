#include "oboe_io.h"

#include <android/log.h>

#define LOG_TAG "rvc-oboe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace rvc {

struct OboeIO::InCb : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
  explicit InCb(VoiceSession* s, std::atomic<bool>* d) : session(s), disc(d) {}
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
    session->on_input(static_cast<const float*>(data), frames);
    return oboe::DataCallbackResult::Continue;
  }
  void onErrorAfterClose(oboe::AudioStream*, oboe::Result r) override {
    LOGE("input stream error: %s", oboe::convertToText(r));
    disc->store(true);
  }
  VoiceSession* session;
  std::atomic<bool>* disc;
};

struct OboeIO::OutCb : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
  explicit OutCb(VoiceSession* s, std::atomic<bool>* d) : session(s), disc(d) {}
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
    session->on_output(static_cast<float*>(data), frames);
    return oboe::DataCallbackResult::Continue;
  }
  void onErrorAfterClose(oboe::AudioStream*, oboe::Result r) override {
    LOGE("output stream error: %s", oboe::convertToText(r));
    disc->store(true);
  }
  VoiceSession* session;
  std::atomic<bool>* disc;
};

static std::atomic<bool> g_disconnected{false};

bool OboeIO::start(int input_device, int output_device, bool unprocessed_mic, bool with_output, std::string* err) {
  stop();
  g_disconnected.store(false);
  in_cb_ = std::make_shared<InCb>(session_, &g_disconnected);
  out_cb_ = std::make_shared<OutCb>(session_, &g_disconnected);

  oboe::AudioStreamBuilder ib;
  ib.setDirection(oboe::Direction::Input)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)
      ->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(oboe::ChannelCount::Mono)
      ->setSampleRate(48000)
      ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
      ->setFormatConversionAllowed(true)
      ->setChannelConversionAllowed(true)
      ->setInputPreset(unprocessed_mic ? oboe::InputPreset::Unprocessed
                                       : oboe::InputPreset::VoicePerformance)
      ->setDeviceId(input_device ? input_device : oboe::kUnspecified)
      ->setDataCallback(in_cb_)
      ->setErrorCallback(in_cb_);
  oboe::Result r = ib.openStream(in_);
  if (r != oboe::Result::OK && input_device != 0) {
    // A specific device (Bluetooth hands-free, USB, ...) often cannot do low-latency / exclusive / unprocessed capture.
    LOGI("input open failed (%s), retrying relaxed for device %d", oboe::convertToText(r), input_device);
    in_.reset();
    ib.setPerformanceMode(oboe::PerformanceMode::None)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setInputPreset(oboe::InputPreset::VoiceCommunication);
    r = ib.openStream(in_);
  }
  if (r != oboe::Result::OK) {
    if (err) *err = std::string("open input: ") + oboe::convertToText(r);
    return false;
  }

  if (with_output) {
    oboe::AudioStreamBuilder ob;
    ob.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(48000)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Speech)
        ->setDeviceId(output_device ? output_device : oboe::kUnspecified)
        ->setDataCallback(out_cb_)
        ->setErrorCallback(out_cb_);
    r = ob.openStream(out_);
    if (r != oboe::Result::OK) {
      in_->close();
      in_.reset();
      if (err) *err = std::string("open output: ") + oboe::convertToText(r);
      return false;
    }
    out_->setBufferSizeInFrames(out_->getFramesPerBurst() * 2);
    }
  exclusive_in_ = in_->getSharingMode() == oboe::SharingMode::Exclusive;
  exclusive_out_ = out_ && out_->getSharingMode() == oboe::SharingMode::Exclusive;
  LOGI("input:  %d Hz, burst %d, exclusive=%d, api=%s", in_->getSampleRate(), in_->getFramesPerBurst(),
       exclusive_in_, oboe::convertToText(in_->getAudioApi()));
  if (out_) LOGI("output: %d Hz, burst %d, exclusive=%d, api=%s", out_->getSampleRate(), out_->getFramesPerBurst(),
                 exclusive_out_, oboe::convertToText(out_->getAudioApi()));

  // capture first so the ring is already filling when playback begins
  if ((r = in_->requestStart()) != oboe::Result::OK || (out_ && (r = out_->requestStart()) != oboe::Result::OK)) {
    if (err) *err = std::string("start: ") + oboe::convertToText(r);
    stop();
    return false;
  }
  if (out_) if (auto lat = out_->calculateLatencyMillis()) out_latency_ms_ = float(lat.value());
  if (auto lat = in_->calculateLatencyMillis()) in_latency_ms_ = float(lat.value());
  return true;
}

void OboeIO::stop() {
  if (out_) { out_->stop(); out_->close(); out_.reset(); }
  if (in_) { in_->stop(); in_->close(); in_.reset(); }
  in_cb_.reset();
  out_cb_.reset();
}

bool OboeIO::disconnected() { return g_disconnected.load(); }

}  // namespace rvc
