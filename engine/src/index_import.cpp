#include "index_import.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

namespace rvc {
namespace {

struct Err { std::string m; };
[[noreturn]] void fail(const std::string& m) { throw Err{m}; }

// ---- float32 <-> float16 (round to nearest even, same as numpy astype(float16))
uint16_t float_to_half(float x) {
  uint32_t b; std::memcpy(&b, &x, 4);
  const uint32_t sign = (b >> 16) & 0x8000u, m = b & 0x7fffffu;
  const uint32_t ef = (b >> 23) & 0xffu;
  if (ef == 0xffu) return uint16_t(sign | 0x7c00u | (m ? 0x200u : 0u));  // inf / nan
  const int32_t e = int32_t(ef) - 127 + 15;
  if (e >= 31) return uint16_t(sign | 0x7c00u);                          // overflow -> inf
  if (e <= 0) {                                                          // subnormal / underflow
    if (e < -10) return uint16_t(sign);
    const uint32_t full = m | 0x800000u;
    const int shift = 14 - e;
    uint32_t h = full >> shift;
    const uint32_t rem = full & ((1u << shift) - 1), half = 1u << (shift - 1);
    if (rem > half || (rem == half && (h & 1u))) ++h;
    return uint16_t(sign | h);
  }
  uint32_t h = sign | (uint32_t(e) << 10) | (m >> 13);
  const uint32_t rem = m & 0x1fffu;
  if (rem > 0x1000u || (rem == 0x1000u && (h & 1u))) ++h;                // carry may bump the exponent: correct
  return uint16_t(h);
}

float half_to_float(uint16_t h) {
  const uint32_t s = (h >> 15) & 1u, e = (h >> 10) & 31u, m = h & 1023u;
  float v;
  if (e == 0) v = std::ldexp(float(m), -24);
  else if (e == 31) v = m ? NAN : INFINITY;
  else v = std::ldexp(float(m | 1024u), int(e) - 25);
  return s ? -v : v;
}

// ---- file reader
struct R {
  std::ifstream f; uint64_t size = 0;
  explicit R(const std::string& p) : f(p, std::ios::binary) {
    if (!f) fail("cannot open the index file");
    f.seekg(0, std::ios::end); size = uint64_t(f.tellg()); f.seekg(0);
  }
  uint64_t pos() { return uint64_t(f.tellg()); }
  void need(uint64_t n) { if (pos() + n > size || pos() + n < pos()) fail("the index file is truncated"); }
  template <class T> T get() { need(sizeof(T)); T v; f.read(reinterpret_cast<char*>(&v), sizeof v); return v; }
  std::string fourcc() { need(4); char c[4]; f.read(c, 4); return std::string(c, 4); }
  void skip(uint64_t n) { need(n); f.seekg(std::streamoff(n), std::ios::cur); }
};

struct Hdr { int32_t d; int64_t n; };

// faiss write_index_header(): d, ntotal, two dummy idx_t, is_trained, metric_type, (metric_arg if metric > 1)
Hdr header(R& r) {
  Hdr h; h.d = r.get<int32_t>(); h.n = r.get<int64_t>();
  r.skip(16); r.get<uint8_t>();
  const int32_t metric = r.get<int32_t>();
  if (metric > 1) r.skip(4);
  if (h.d <= 0 || h.d > 8192 || h.n < 0) fail("this is not a faiss index file (bad header)");
  return h;
}

bool is_flat(const std::string& c) { return c == "IxF2" || c == "IxFl" || c == "IxFI"; }

// element count of a flat index's storage vector: floats (faiss <= 1.7) or bytes (faiss >= 1.8)
uint64_t flat_bytes(R& r, const Hdr& h) {
  const uint64_t cnt = r.get<uint64_t>(), want = uint64_t(h.n) * uint64_t(h.d);
  if (cnt == want) return cnt * 4;
  if (cnt == want * 4) return cnt;
  fail("unexpected flat-index layout");
}

// ---- output writer: index.bin = "RVCI", n, d, 1, f32 sqnorm[n], f16 vecs[n*d]
struct Sink {
  std::ofstream o; uint64_t n, done = 0; int d; std::vector<float> sq; std::vector<uint16_t> buf;
  Sink(const std::string& path, uint64_t n_, int d_) : o(path, std::ios::binary | std::ios::trunc), n(n_), d(d_) {
    if (!o) fail("cannot write " + path);
    std::vector<char> z(16 + n * 4, 0);  // header + norms are filled in finish()
    o.write(z.data(), std::streamsize(z.size()));
    sq.reserve(n);
  }
  void add(const float* v, size_t rows) {
    if (done + rows > n) fail("the index holds more vectors than it announces");
    buf.resize(rows * size_t(d));
    for (size_t r = 0; r < rows; ++r) {
      float s = 0;
      for (int j = 0; j < d; ++j) {
        const uint16_t h = float_to_half(v[r * size_t(d) + size_t(j)]);
        buf[r * size_t(d) + size_t(j)] = h;
        const float x = half_to_float(h); s += x * x;
      }
      sq.push_back(s);
    }
    o.write(reinterpret_cast<const char*>(buf.data()), std::streamsize(buf.size() * 2));
    done += rows;
  }
  void finish() {
    if (done != n) fail("the index holds fewer vectors than it announces");
    o.seekp(0);
    const uint32_t hd[3] = {uint32_t(n), uint32_t(d), 1u};
    o.write("RVCI", 4); o.write(reinterpret_cast<const char*>(hd), 12);
    o.write(reinterpret_cast<const char*>(sq.data()), std::streamsize(sq.size() * 4));
    o.flush();
    if (!o) fail("write failed (disk full?)");
  }
};

void copy_vectors(R& r, Sink& s, uint64_t rows) {  // rows x d float32 straight from the file
  const size_t chunk = 1024; std::vector<float> b(chunk * size_t(s.d));
  while (rows) {
    const size_t k = size_t(std::min<uint64_t>(rows, chunk));
    r.need(uint64_t(k) * uint64_t(s.d) * 4);
    r.f.read(reinterpret_cast<char*>(b.data()), std::streamsize(k * size_t(s.d) * 4));
    s.add(b.data(), k); rows -= k;
  }
}

void check_dims(const Hdr& h, int expect_dim) {
  if (h.n == 0) fail("the index contains no vectors (use the \"added_...\" index, not \"trained_...\")");
  if (h.d % 8 != 0) fail("unsupported index dimension " + std::to_string(h.d));
  if (expect_dim > 0 && h.d != expect_dim)
    fail("the index has " + std::to_string(h.d) + "-dimensional features but this voice needs " + std::to_string(expect_dim) +
         " (it belongs to a different model)");
}

}  // namespace

bool index_import(const std::string& in, const std::string& out, int expect_dim, int* n_out, int* d_out, std::string* err) {
  try {
    R r(in);
    if (r.size < 32) fail("this is not a faiss index file");
    const std::string cc = r.fourcc();

    if (cc == "IwFl") {                                   // IndexIVFFlat (what RVC training writes)
      const Hdr h = header(r);
      const uint64_t nlist = r.get<uint64_t>(); r.get<uint64_t>();  // nlist, nprobe
      {                                                   // coarse quantizer: an IndexFlat, skipped
        const std::string qc = r.fourcc();
        if (!is_flat(qc)) fail("unsupported coarse quantizer '" + qc + "'");
        const Hdr qh = header(r); r.skip(flat_bytes(r, qh));
      }
      const uint8_t dm = r.get<uint8_t>();                // direct map
      r.skip(r.get<uint64_t>() * 8);
      if (dm == 2) { r.skip(r.get<uint64_t>() * 8); r.skip(r.get<uint64_t>() * 8); }
      const std::string il = r.fourcc();
      if (il != "ilar") fail("unsupported inverted-list storage '" + il + "'");
      const uint64_t nl2 = r.get<uint64_t>(), code_size = r.get<uint64_t>();
      if (nl2 != nlist) fail("corrupt index (list count)");
      if (code_size != uint64_t(h.d) * 4) fail("this index is compressed (not \"Flat\"); only IVF...,Flat indexes are supported");
      const std::string lt = r.fourcc();
      const uint64_t cnt = r.get<uint64_t>();
      r.need(cnt * 8);
      std::vector<uint64_t> sizes(nlist, 0);
      if (lt == "full") {
        if (cnt != nlist) fail("corrupt index (list sizes)");
        for (uint64_t i = 0; i < nlist; ++i) sizes[i] = r.get<uint64_t>();
      } else if (lt == "sprs") {
        if (cnt % 2) fail("corrupt index (sparse list sizes)");
        for (uint64_t k = 0; k < cnt / 2; ++k) {
          const uint64_t i = r.get<uint64_t>(), n = r.get<uint64_t>();
          if (i >= nlist) fail("corrupt index (list id)");
          sizes[i] = n;
        }
      } else fail("unknown inverted-list layout '" + lt + "'");
      uint64_t total = 0; for (auto s : sizes) total += s;
      if (total != uint64_t(h.n)) fail("corrupt index (vector count)");
      check_dims(h, expect_dim);
      Sink s(out, uint64_t(h.n), h.d);
      for (uint64_t i = 0; i < nlist; ++i) {              // per list: n codes, then n int64 ids (ids are not needed)
        if (!sizes[i]) continue;
        copy_vectors(r, s, sizes[i]); r.skip(sizes[i] * 8);
      }
      s.finish();
      if (n_out) *n_out = int(h.n);
      if (d_out) *d_out = h.d;
      return true;
    }

    if (is_flat(cc)) {                                    // plain IndexFlat
      const Hdr h = header(r);
      check_dims(h, expect_dim);
      const uint64_t bytes = flat_bytes(r, h);
      (void)bytes;
      Sink s(out, uint64_t(h.n), h.d);
      copy_vectors(r, s, uint64_t(h.n));
      s.finish();
      if (n_out) *n_out = int(h.n);
      if (d_out) *d_out = h.d;
      return true;
    }
    fail("unsupported index type '" + cc + "' (RVC indexes are IVF...,Flat)");
  } catch (const Err& e) { if (err) *err = e.m; std::remove(out.c_str()); return false; }
}

}  // namespace rvc
