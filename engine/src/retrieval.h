// Brute-force k-NN feature retrieval (RVC "index rate") over an mmap'd FP16 index.
// Format written by export/build_index.py (magic "RVCI").
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace rvc {

class RetrievalIndex {
 public:
  RetrievalIndex() = default;
  ~RetrievalIndex();
  RetrievalIndex(const RetrievalIndex&) = delete;
  RetrievalIndex& operator=(const RetrievalIndex&) = delete;

  bool load(const std::string& path, std::string* err = nullptr);
  bool loaded() const { return vecs_ != nullptr; }
  int count() const { return n_; }
  int dim() const { return d_; }

  // feats: T x d row-major (T <= 16). In place:
  //   feats = rate * sum_k w_k * neigh_k + (1 - rate) * feats
  // with w_k ~ (1/squared_L2)^2, k = 8, exactly as rtrvc.py does.
  void blend(float* feats, int T, float rate, int threads = 2) const;

  static float half_to_float(uint16_t h);  // exposed for tests

 private:
  static constexpr int K = 8;
  struct TopK {
    float s[K];
    int ix[K];
    int worst;
    void reset();
    void push(float score, int idx);
  };
  void search_range(const float* q, const float* qn, int Tpad, int T, int lo, int hi,
                    TopK* out) const;

  void* map_ = nullptr;
  size_t map_len_ = 0;
  int n_ = 0, d_ = 0;
  const float* norms_ = nullptr;
  const uint16_t* vecs_ = nullptr;
};

}  // namespace rvc
