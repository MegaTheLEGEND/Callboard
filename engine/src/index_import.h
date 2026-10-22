// On-device converter: faiss .index (RVC's "added_IVF*_Flat*.index", or a plain IndexFlat) -> the engine's index.bin
// (magic "RVCI", FP16 vectors + squared norms; same layout as export/build_index.py). No faiss needed: the file is
// parsed directly and only the stored vectors are kept (retrieval is brute force, see retrieval.cpp).
#pragma once
#include <string>

namespace rvc {

// expect_dim: feature size of the voice (768 for v2, 256 for v1); 0 = accept any multiple of 8.
// On success *n_out / *d_out hold the vector count and dimension. On failure *err says why.
bool index_import(const std::string& faiss_path, const std::string& out_bin, int expect_dim, int* n_out, int* d_out,
                  std::string* err);

}  // namespace rvc
