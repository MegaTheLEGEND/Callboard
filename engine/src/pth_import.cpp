#include "pth_import.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <vector>

namespace rvc {
namespace {

struct Err { std::string m; };
[[noreturn]] void fail(const std::string& m) { throw Err{m}; }

// ------------------------------------------------------------------ values
struct Val;
using VP = std::shared_ptr<Val>;
struct Val {
  enum T { None, Bool, Int, Float, Str, List, Tuple, Dict, Global, Storage, Tensor, Mark } t = None;
  int64_t i = 0;  // Int/Bool, tensor offset
  double f = 0;
  std::string s;  // Str / Global "mod.name" / Storage key / Tensor storage key
  std::string dt; // Storage / Tensor dtype
  std::vector<VP> v;
  std::vector<std::pair<VP, VP>> d;
  std::vector<int64_t> dims, strides;
};
VP mk(Val::T t) { auto p = std::make_shared<Val>(); p->t = t; return p; }

VP get(const VP& d, const std::string& k) {
  if (d && d->t == Val::Dict) for (auto& kv : d->d) if (kv.first->t == Val::Str && kv.first->s == k) return kv.second;
  return nullptr;
}

// ------------------------------------------------------------------ zip (stored entries only)
struct Zip {
  std::ifstream f;
  struct E { uint64_t off, size; int method; };
  std::map<std::string, E> e;
  static uint32_t u32(const unsigned char* p) { return p[0] | p[1] << 8 | p[2] << 16 | uint32_t(p[3]) << 24; }
  static uint16_t u16(const unsigned char* p) { return p[0] | p[1] << 8; }
  explicit Zip(const std::string& path) : f(path, std::ios::binary) {
    if (!f) fail("cannot open " + path);
    f.seekg(0, std::ios::end);
    const int64_t n = f.tellg();
    const int64_t tail = std::min<int64_t>(n, 65557);
    std::vector<unsigned char> b(tail);
    f.seekg(n - tail); f.read(reinterpret_cast<char*>(b.data()), tail);
    int64_t p = tail - 22;
    while (p >= 0 && u32(&b[p]) != 0x06054b50) --p;
    if (p < 0) fail("not a zip/PyTorch checkpoint");
    const uint32_t cnt = u16(&b[p + 10]), cdoff = u32(&b[p + 16]);
    f.seekg(cdoff);
    for (uint32_t k = 0; k < cnt; ++k) {
      unsigned char h[46];
      if (!f.read(reinterpret_cast<char*>(h), 46) || u32(h) != 0x02014b50) fail("corrupt zip directory");
      std::string name(u16(h + 28), '\0');
      f.read(name.data(), name.size());
      f.seekg(u16(h + 30) + u16(h + 32), std::ios::cur);
      e[name] = {u32(h + 42), u32(h + 24), u16(h + 10)};
    }
  }
  std::string find_suffix(const std::string& suf) const {
    for (auto& kv : e) if (kv.first.size() >= suf.size() && kv.first.compare(kv.first.size() - suf.size(), suf.size(), suf) == 0) return kv.first;
    return "";
  }
  // returns offset of the entry's data in the file
  uint64_t data_off(const std::string& name, uint64_t* size) {
    auto it = e.find(name);
    if (it == e.end()) fail("missing entry " + name);
    if (it->second.method != 0) fail("compressed checkpoint entries are not supported (re-save with torch.save)");
    unsigned char h[30];
    f.clear(); f.seekg(it->second.off); f.read(reinterpret_cast<char*>(h), 30);
    if (u32(h) != 0x04034b50) fail("corrupt zip entry");
    *size = it->second.size;
    return it->second.off + 30 + u16(h + 26) + u16(h + 28);
  }
  std::string read(const std::string& name) {
    uint64_t sz; const uint64_t o = data_off(name, &sz);
    std::string out(sz, '\0');
    f.clear(); f.seekg(o); f.read(out.data(), sz);
    return out;
  }
};

// ------------------------------------------------------------------ pickle VM (builds data only)
VP run_pickle(const std::string& b) {
  std::vector<VP> st; std::map<uint32_t, VP> memo; uint32_t next_memo = 0;
  size_t p = 0;
  auto need = [&](size_t n) { if (p + n > b.size()) fail("truncated pickle"); };
  auto rd = [&](int n) { need(n); uint64_t v = 0; for (int k = 0; k < n; ++k) v |= uint64_t(uint8_t(b[p + k])) << (8 * k); p += n; return v; };
  auto pop = [&]() { if (st.empty()) fail("pickle stack underflow"); VP x = st.back(); st.pop_back(); return x; };
  auto mark_idx = [&]() { for (size_t k = st.size(); k-- > 0;) if (st[k]->t == Val::Mark) return k; fail("pickle mark missing"); };
  auto pop_mark = [&]() { size_t m = mark_idx(); std::vector<VP> r(st.begin() + m + 1, st.end()); st.resize(m); return r; };
  auto mkint = [&](int64_t x) { auto v = mk(Val::Int); v->i = x; return v; };
  auto mkstr = [&](const std::string& s) { auto v = mk(Val::Str); v->s = s; return v; };
  auto tuple_of = [&](std::vector<VP> v) { auto t = mk(Val::Tuple); t->v = std::move(v); return t; };
  auto reduce = [&](const VP& fn, const VP& args) -> VP {
    if (fn->t != Val::Global) fail("unexpected callable in pickle");
    const auto& g = fn->s;
    if (g == "collections.OrderedDict") return mk(Val::Dict);
    if (g == "torch._utils._rebuild_parameter") return args->v.at(0);
    if (g == "torch._utils._rebuild_tensor_v2" || g == "torch._utils._rebuild_tensor") {
      auto t = mk(Val::Tensor); const auto& s = args->v.at(0);
      if (s->t != Val::Storage) fail("tensor without storage");
      t->s = s->s; t->dt = s->dt; t->i = args->v.at(1)->i;
      for (auto& x : args->v.at(2)->v) t->dims.push_back(x->i);
      for (auto& x : args->v.at(3)->v) t->strides.push_back(x->i);
      return t;
    }
    if (g == "torch.Size") { return args->v.empty() ? tuple_of({}) : args->v.at(0); }
    fail("checkpoint uses " + g + " (not a plain RVC checkpoint)");
  };
  for (;;) {
    need(1);
    const unsigned char op = b[p++];
    switch (op) {
      case 0x80: rd(1); break;
      case 0x95: rd(8); break;
      case '}': st.push_back(mk(Val::Dict)); break;
      case ']': st.push_back(mk(Val::List)); break;
      case ')': st.push_back(mk(Val::Tuple)); break;
      case '(': st.push_back(mk(Val::Mark)); break;
      case 'q': memo[rd(1)] = st.back(); break;
      case 'r': memo[rd(4)] = st.back(); break;
      case 0x94: memo[next_memo++] = st.back(); break;
      case 'h': { auto it = memo.find(rd(1)); if (it == memo.end()) fail("bad memo"); st.push_back(it->second); break; }
      case 'j': { auto it = memo.find(rd(4)); if (it == memo.end()) fail("bad memo"); st.push_back(it->second); break; }
      case 'X': { size_t n = rd(4); need(n); st.push_back(mkstr(b.substr(p, n))); p += n; break; }
      case 0x8c: { size_t n = rd(1); need(n); st.push_back(mkstr(b.substr(p, n))); p += n; break; }
      case 'U': { size_t n = rd(1); need(n); st.push_back(mkstr(b.substr(p, n))); p += n; break; }
      case 'K': st.push_back(mkint(int64_t(rd(1)))); break;
      case 'M': st.push_back(mkint(int64_t(rd(2)))); break;
      case 'J': st.push_back(mkint(int32_t(rd(4)))); break;
      case 0x8a: { int n = int(rd(1)); need(n); int64_t v = 0; for (int k = 0; k < n && k < 8; ++k) v |= int64_t(uint8_t(b[p + k])) << (8 * k); p += n; st.push_back(mkint(v)); break; }
      case 'G': { need(8); uint64_t u = 0; for (int k = 0; k < 8; ++k) u = (u << 8) | uint8_t(b[p + k]); p += 8; double d; memcpy(&d, &u, 8); auto v = mk(Val::Float); v->f = d; st.push_back(v); break; }
      case 'N': st.push_back(mk(Val::None)); break;
      case 0x88: { auto v = mk(Val::Bool); v->i = 1; st.push_back(v); break; }
      case 0x89: st.push_back(mk(Val::Bool)); break;
      case 'c': {
        size_t e1 = b.find('\n', p), e2 = b.find('\n', e1 + 1);
        if (e1 == std::string::npos || e2 == std::string::npos) fail("bad GLOBAL");
        auto g = mk(Val::Global); g->s = b.substr(p, e1 - p) + "." + b.substr(e1 + 1, e2 - e1 - 1); p = e2 + 1; st.push_back(g); break;
      }
      case 0x93: { auto n = pop(), m = pop(); auto g = mk(Val::Global); g->s = m->s + "." + n->s; st.push_back(g); break; }
      case 'R': { auto a = pop(), f = pop(); st.push_back(reduce(f, a)); break; }
      case 'b': pop(); break;  // BUILD: state is ignored (state_dict _metadata)
      case 'Q': {
        auto pid = pop();
        if (pid->t != Val::Tuple || pid->v.size() < 3 || pid->v[0]->s != "storage") fail("unexpected persistent id");
        auto s = mk(Val::Storage); s->s = pid->v[2]->s;
        const std::string& cls = pid->v[1]->s;  // torch.HalfStorage ...
        s->dt = cls.substr(cls.rfind('.') + 1);
        st.push_back(s); break;
      }
      case 's': { auto v = pop(), k = pop(); st.back()->d.push_back({k, v}); break; }
      case 'u': { auto it = pop_mark(); for (size_t k = 0; k + 1 < it.size(); k += 2) st.back()->d.push_back({it[k], it[k + 1]}); break; }
      case 'a': { auto v = pop(); st.back()->v.push_back(v); break; }
      case 'e': { auto it = pop_mark(); for (auto& x : it) st.back()->v.push_back(x); break; }
      case 't': { auto it = pop_mark(); st.push_back(tuple_of(it)); break; }
      case 0x85: { auto a = pop(); st.push_back(tuple_of({a})); break; }
      case 0x86: { auto b2 = pop(), a = pop(); st.push_back(tuple_of({a, b2})); break; }
      case 0x87: { auto c = pop(), b2 = pop(), a = pop(); st.push_back(tuple_of({a, b2, c})); break; }
      case '0': pop(); break;
      case '.': return st.empty() ? nullptr : st.back();
      default: fail("unsupported pickle opcode " + std::to_string(op));
    }
  }
}

// ------------------------------------------------------------------ tensors
struct Ckpt {
  std::unique_ptr<Zip> z; std::string root; VP top, weight;
};

Ckpt open(const std::string& path) {
  Ckpt c; c.z = std::make_unique<Zip>(path);
  std::string pk = c.z->find_suffix("data.pkl");
  if (pk.empty()) fail("no data.pkl (not a PyTorch zip checkpoint)");
  c.root = pk.substr(0, pk.size() - 8);
  c.top = run_pickle(c.z->read(pk));
  if (!c.top || c.top->t != Val::Dict) fail("not an RVC voice checkpoint");
  c.weight = get(c.top, "weight");
  if (!c.weight || !get(c.top, "config") || get(c.top, "config")->v.size() < 18) fail("not an RVC voice checkpoint (no weight/config)");
  return c;
}

int64_t numel(const std::vector<int64_t>& d) { int64_t n = 1; for (auto x : d) n *= x; return n; }

float half_to_float(uint16_t h) {
  const uint32_t s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
  float v;
  if (e == 0) v = std::ldexp(float(m), -24);
  else if (e == 31) v = m ? NAN : INFINITY;
  else v = std::ldexp(float(m | 1024), int(e) - 25);
  return s ? -v : v;
}

std::vector<float> tensor_f32(Ckpt& c, const VP& t) {
  int64_t expect = 1;
  for (size_t k = t->dims.size(); k-- > 0;) { if (t->dims[k] != 1 && t->strides[k] != expect) fail("non-contiguous tensor in checkpoint"); expect *= t->dims[k]; }
  const int64_t n = numel(t->dims);
  int es = t->dt == "HalfStorage" || t->dt == "BFloat16Storage" ? 2 : t->dt == "FloatStorage" ? 4 : t->dt == "DoubleStorage" ? 8 : 0;
  if (!es) fail("unsupported tensor dtype " + t->dt);
  uint64_t sz; const uint64_t off = c.z->data_off(c.root + "data/" + t->s, &sz);
  if (uint64_t(t->i + n) * es > sz) fail("tensor outside its storage");
  std::vector<unsigned char> raw(n * es);
  c.z->f.clear(); c.z->f.seekg(off + t->i * es); c.z->f.read(reinterpret_cast<char*>(raw.data()), raw.size());
  std::vector<float> o(n);
  for (int64_t k = 0; k < n; ++k) {
    const unsigned char* q = &raw[k * es];
    if (t->dt == "HalfStorage") o[k] = half_to_float(uint16_t(q[0] | q[1] << 8));
    else if (t->dt == "BFloat16Storage") { uint32_t u = uint32_t(q[0] | q[1] << 8) << 16; memcpy(&o[k], &u, 4); }
    else if (t->dt == "FloatStorage") memcpy(&o[k], q, 4);
    else { double d; memcpy(&d, q, 8); o[k] = float(d); }
  }
  return o;
}

int sr_of(const VP& v) {
  if (v->t == Val::Int) return int(v->i);
  if (v->t == Val::Str) return v->s == "32k" ? 32000 : v->s == "40k" ? 40000 : v->s == "48k" ? 48000 : 0;
  return 0;
}

// Canonical text of the architecture-defining config entries (must match build_base.py canon()).
void canon(const VP& v, std::string* o) {
  switch (v->t) {
    case Val::Int: case Val::Bool: *o += std::to_string(v->i); break;
    case Val::Float: { char b[32]; snprintf(b, sizeof b, "%g", v->f); *o += b; break; }
    case Val::Str: {
      bool digits = !v->s.empty();
      for (char ch : v->s) digits &= (ch >= '0' && ch <= '9');
      if (digits) *o += v->s; else *o += "\"" + v->s + "\"";
      break;
    }
    case Val::List: case Val::Tuple: {
      *o += "[";
      for (size_t k = 0; k < v->v.size(); ++k) { if (k) *o += ","; canon(v->v[k], o); }
      *o += "]"; break;
    }
    default: *o += "?";
  }
}
std::string config_canon(const VP& cfg) {
  std::string o;
  for (int k : {2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 16}) { canon(cfg->v[k], &o); o += ";"; }
  o += std::to_string(sr_of(cfg->v[17]));
  return o;
}

}  // namespace

// ------------------------------------------------------------------ public API
bool pth_inspect(const std::string& pth, PthInfo* o, std::string* err) {
  try {
    Ckpt c = open(pth);
    auto cfg = get(c.top, "config");
    auto ver = get(c.top, "version"); auto f0 = get(c.top, "f0");
    o->version = ver && ver->t == Val::Str ? ver->s : "v1";
    o->f0 = f0 ? int(f0->i) : 1;
    o->sr = sr_of(cfg->v[17]);
    auto emb = get(c.weight, "emb_g.weight");
    o->speakers = emb && !emb->dims.empty() ? int(emb->dims[0]) : 1;
    int64_t upp = 1; for (auto& r : cfg->v[12]->v) upp *= r->i;
    o->reason.clear();
    if (o->version != "v1" && o->version != "v2") o->reason = "unknown model version " + o->version;
    else if (o->f0 != 1) o->reason = "trained without pitch (f0=0); only pitch-guided voices work";
    else if (o->sr != 32000 && o->sr != 40000 && o->sr != 48000) o->reason = "unsupported sample rate " + std::to_string(o->sr);
    else if (upp * 100 != o->sr) o->reason = "unsupported vocoder layout";
    else if (!get(c.weight, "dec.m_source.l_linear.weight")) o->reason = "no NSF source module in checkpoint";
    return true;
  } catch (const Err& e) { if (err) *err = e.m; return false; }
}

bool pth_import(const std::string& pth, const std::string& tdir, const std::string& out, int sid, std::string* err) {
  try {
    Ckpt c = open(pth);
    // ---- template description
    struct P { std::string name; std::vector<int64_t> dims; };
    std::vector<P> params; std::string tcfg, tconfig;
    {
      std::ifstream f(tdir + "/template.params");
      if (!f) fail("template files missing in " + tdir);
      std::string line;
      while (std::getline(f, line)) {
        std::istringstream ss(line); P p; ss >> p.name; int64_t d; while (ss >> d) p.dims.push_back(d);
        if (!p.name.empty()) params.push_back(p);
      }
      std::ifstream g(tdir + "/template.config"); std::getline(g, tconfig);
      std::ifstream h(tdir + "/template.cfg"); std::stringstream hs; hs << h.rdbuf(); tcfg = hs.str();
    }
    const std::string mine = config_canon(get(c.top, "config"));
    if (mine != tconfig) fail("this voice's architecture does not match the installed template (" + mine + ")");

    std::ofstream w(out + "/weights.bin", std::ios::binary);
    if (!w) fail("cannot write " + out + "/weights.bin");
    const uint32_t ver = 1, cnt = uint32_t(params.size());
    w.write("RVCW", 4); w.write(reinterpret_cast<const char*>(&ver), 4); w.write(reinterpret_cast<const char*>(&cnt), 4);

    for (auto& p : params) {
      std::vector<float> data; std::vector<int64_t> dims;
      if (p.name == "g") {
        auto emb = get(c.weight, "emb_g.weight");
        if (!emb || emb->dims.size() != 2) fail("checkpoint has no emb_g.weight");
        if (sid < 0 || sid >= emb->dims[0]) fail("speaker id out of range");
        auto all = tensor_f32(c, emb);
        data.assign(all.begin() + sid * emb->dims[1], all.begin() + (sid + 1) * emb->dims[1]);
        dims = {1, emb->dims[1], 1};
      } else {
        if (p.name.rfind("net_g.", 0) != 0) fail("unexpected template parameter " + p.name);
        const std::string key = p.name.substr(6);
        auto direct = get(c.weight, key);
        const std::string base = key.size() > 7 && key.compare(key.size() - 7, 7, ".weight") == 0 ? key.substr(0, key.size() - 7) : "";
        auto gt = base.empty() ? nullptr : get(c.weight, base + ".weight_g"), vt = base.empty() ? nullptr : get(c.weight, base + ".weight_v");
        if (gt && vt) {  // weight-norm: w = v * g / ||v|| (per output channel)
          auto v = tensor_f32(c, vt), g = tensor_f32(c, gt);
          dims = vt->dims; const int64_t O = dims.at(0), per = numel(dims) / O;
          if (int64_t(g.size()) != O) fail("bad weight_g for " + key);
          for (int64_t o2 = 0; o2 < O; ++o2) {
            double s = 0; for (int64_t k = 0; k < per; ++k) s += double(v[o2 * per + k]) * v[o2 * per + k];
            const float sc = g[o2] / float(std::sqrt(s) + 1e-12);
            for (int64_t k = 0; k < per; ++k) v[o2 * per + k] *= sc;
          }
          data = std::move(v);
        } else if (direct) { dims = direct->dims; data = tensor_f32(c, direct); }
        else fail("checkpoint is missing " + key);
      }
      if (dims != p.dims) fail("shape mismatch for " + p.name);
      const uint16_t nl = uint16_t(p.name.size()); const uint8_t nd = uint8_t(dims.size());
      w.write(reinterpret_cast<const char*>(&nl), 2); w.write(p.name.data(), nl);
      w.write(reinterpret_cast<const char*>(&nd), 1);
      for (auto d : dims) w.write(reinterpret_cast<const char*>(&d), 8);
      w.write(reinterpret_cast<const char*>(data.data()), std::streamsize(data.size() * 4));
    }
    if (!w) fail("write failed (disk full?)");
    w.close();

    // ---- engine.cfg = template geometry + this voice's NSF source constants
    auto lw = get(c.weight, "dec.m_source.l_linear.weight"), lb = get(c.weight, "dec.m_source.l_linear.bias");
    if (!lw || !lb) fail("no NSF source module");
    const float sw = tensor_f32(c, lw).at(0), sb = tensor_f32(c, lb).at(0);
    std::ofstream e(out + "/engine.cfg");
    char buf[96]; snprintf(buf, sizeof buf, "source_lin_w %.9g\nsource_lin_b %.9g\nhas_index 0\n", sw, sb);
    e << tcfg << buf;
    if (!e) fail("cannot write engine.cfg");
    return true;
  } catch (const Err& x) { if (err) *err = x.m; return false; }
}

}  // namespace rvc
