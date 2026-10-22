// Stress test: producer/consumer threads with irregular chunk sizes must preserve order and content.
#include <atomic>
#include <cstdio>
#include <thread>
#include <vector>
#include "../src/ring_buffer.h"

int main() {
  rvc::SpscRing ring(4800 * 3);
  const size_t total = 20'000'000;
  std::atomic<bool> bad{false};
  std::thread prod([&] {
    std::vector<float> chunk(1000);
    size_t sent = 0;
    unsigned s = 1;
    while (sent < total) {
      s = s * 1664525u + 1013904223u;
      size_t n = std::min<size_t>(1 + (s >> 16) % 960, total - sent);
      for (size_t i = 0; i < n; ++i) chunk[i] = float((sent + i) % 1000003);
      size_t w = 0;
      while (w < n) { w += ring.write(chunk.data() + w, n - w); }
      sent += n;
    }
  });
  std::thread cons([&] {
    std::vector<float> chunk(1000);
    size_t got = 0;
    unsigned s = 7;
    while (got < total) {
      s = s * 1664525u + 1013904223u;
      size_t n = ring.read(chunk.data(), 1 + (s >> 16) % 960);
      for (size_t i = 0; i < n; ++i)
        if (chunk[i] != float((got + i) % 1000003)) { bad = true; got = total; break; }
      got += n;
    }
  });
  prod.join(); cons.join();
  // skip / zeros / wraparound
  rvc::SpscRing r(10);
  float a[7] = {1,2,3,4,5,6,7}, o[10];
  r.write(a, 7); r.skip(5); r.write(a, 7);          // wraps
  size_t n = r.read(o, 10);
  bool ok = n == 9 && o[0] == 6 && o[1] == 7 && o[2] == 1 && o[8] == 7;
  r.write_zeros(4); ok = ok && r.available() == 4 && r.space() == 6;
  std::printf("ring stress %s, wraparound %s\n", bad ? "FAILED" : "ok", ok ? "ok" : "FAILED");
  return (bad || !ok) ? 1 : 0;
}
