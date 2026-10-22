#include <cstdio>
#include <cstdlib>
#include "../src/index_import.h"
#include "../src/retrieval.h"
int main(int argc, char** argv) {  // test_index_import <faiss.index> <out index.bin> <expect_dim 0|256|768>
  if (argc < 4) { printf("usage: test_index_import <faiss.index> <out.bin> <dim>\n"); return 1; }
  int n = 0, d = 0; std::string e;
  if (!rvc::index_import(argv[1], argv[2], atoi(argv[3]), &n, &d, &e)) { printf("IMPORT_FAIL %s\n", e.c_str()); return 2; }
  printf("IMPORT_OK n=%d d=%d\n", n, d);
  rvc::RetrievalIndex idx;  // the engine's own loader must accept what we wrote
  if (!idx.load(argv[2], &e)) { printf("ENGINE_LOAD_FAIL %s\n", e.c_str()); return 3; }
  printf("ENGINE_LOAD_OK n=%d d=%d\n", idx.count(), idx.dim());
}
