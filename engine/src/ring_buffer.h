// Single-producer / single-consumer lock-free ring buffer of floats.
// Used between the Oboe callbacks (real-time threads) and the inference thread.
// No allocation, no locks, no syscalls on the hot path.
#pragma once
#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstring>
#include <vector>

namespace rvc {

class SpscRing {
 public:
  explicit SpscRing(size_t capacity = 0) { resize(capacity); }
  void resize(size_t capacity) {  // not thread-safe; call before streaming
    cap_ = capacity + 1;
    buf_.assign(cap_, 0.f);
    head_.store(0);
    tail_.store(0);
  }
  size_t capacity() const { return cap_ - 1; }

  size_t available() const {  // readable samples
    const size_t h = head_.load(std::memory_order_acquire), t = tail_.load(std::memory_order_relaxed);
    return h >= t ? h - t : h + cap_ - t;
  }
  size_t space() const { return capacity() - available(); }

  // producer: returns samples written (may be < n if full)
  size_t write(const float* src, size_t n) {
    const size_t h = head_.load(std::memory_order_relaxed);
    n = std::min(n, space());
    const size_t first = std::min(n, cap_ - h);
    std::memcpy(&buf_[h], src, first * sizeof(float));
    if (n > first) std::memcpy(&buf_[0], src + first, (n - first) * sizeof(float));
    head_.store((h + n) % cap_, std::memory_order_release);
    return n;
  }
  size_t write_zeros(size_t n) {
    const size_t h = head_.load(std::memory_order_relaxed);
    n = std::min(n, space());
    const size_t first = std::min(n, cap_ - h);
    std::memset(&buf_[h], 0, first * sizeof(float));
    if (n > first) std::memset(&buf_[0], 0, (n - first) * sizeof(float));
    head_.store((h + n) % cap_, std::memory_order_release);
    return n;
  }

  // consumer: returns samples read (may be < n if empty)
  size_t read(float* dst, size_t n) {
    const size_t t = tail_.load(std::memory_order_relaxed);
    n = std::min(n, available());
    const size_t first = std::min(n, cap_ - t);
    std::memcpy(dst, &buf_[t], first * sizeof(float));
    if (n > first) std::memcpy(dst + first, &buf_[0], (n - first) * sizeof(float));
    tail_.store((t + n) % cap_, std::memory_order_release);
    return n;
  }
  size_t skip(size_t n) {  // consumer: drop n samples
    const size_t t = tail_.load(std::memory_order_relaxed);
    n = std::min(n, available());
    tail_.store((t + n) % cap_, std::memory_order_release);
    return n;
  }

 private:
  std::vector<float> buf_;
  size_t cap_ = 1;
  alignas(64) std::atomic<size_t> head_{0};
  alignas(64) std::atomic<size_t> tail_{0};
};

}  // namespace rvc
