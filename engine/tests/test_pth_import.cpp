#include <cstdio>
#include "../src/pth_import.h"
int main(int argc, char** argv) {  // test_pth_import <pth> <template_dir> <out_dir> <sid>
  rvc::PthInfo i; std::string e;
  if (!rvc::pth_inspect(argv[1], &i, &e)) { printf("INSPECT_FAIL %s\n", e.c_str()); return 2; }
  printf("INFO %s %d f0=%d spk=%d reason=[%s]\n", i.version.c_str(), i.sr, i.f0, i.speakers, i.reason.c_str());
  if (!rvc::pth_import(argv[1], argv[2], argv[3], atoi(argv[4]), &e)) { printf("IMPORT_FAIL %s\n", e.c_str()); return 3; }
  printf("IMPORT_OK\n");
}
