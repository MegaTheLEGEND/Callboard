cmake_minimum_required(VERSION 3.22)
project(rvc_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
# NOTE: deliberately no -ffast-math: fmod/nearbyint/NaN semantics matter for the
# sine source and pitch quantisation (they are unit-tested against Python).
set(CMAKE_CXX_FLAGS_RELEASE "-O3")

set(CORE_SRC
  src/dsp.cpp
  src/retrieval.cpp
  src/ort_graph.cpp
  src/pth_import.cpp
  src/engine.cpp
  src/voice_session.cpp)

if(ANDROID)
  # ----------------------------------------------------------------- Android --
  # ORT + QNN come from the onnxruntime-android-qnn AAR, unpacked by scripts/fetch_ort.sh
  set(ORT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ort)
  if(NOT EXISTS ${ORT_DIR}/headers/onnxruntime_cxx_api.h)
    message(FATAL_ERROR "ONNX Runtime not found. Run scripts/fetch_ort.sh first.")
  endif()
  find_package(oboe REQUIRED CONFIG)          # Oboe via Gradle prefab

  add_library(onnxruntime SHARED IMPORTED)
  set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so)

  add_library(rvcengine SHARED ${CORE_SRC} src/oboe_io.cpp src/jni_bridge.cpp)
  target_include_directories(rvcengine PRIVATE ${ORT_DIR}/headers src)
  target_link_libraries(rvcengine PRIVATE oboe::oboe onnxruntime log android)
  # Android 15+ devices may use 16 KB pages
  target_link_options(rvcengine PRIVATE -Wl,-z,max-page-size=16384)
else()
  # -------------------------------------------------------------------- Host --
  # cmake -S engine -B build -DORT_HOST_DIR=/path/to/onnxruntime-linux-x64-1.24.4
  set(ORT_HOST_DIR "" CACHE PATH "unpacked onnxruntime-linux-x64 release")
  include(CheckCXXCompilerFlag)
  check_cxx_compiler_flag("-mavx2 -mfma -mf16c" HAVE_AVX2)
  if(HAVE_AVX2)
    add_compile_options(-mavx2 -mfma -mf16c)
  endif()
  find_package(Threads REQUIRED)

  add_executable(test_dsp tests/test_dsp.cpp src/dsp.cpp src/retrieval.cpp)
  target_link_libraries(test_dsp Threads::Threads)
  add_executable(test_pth_import tests/test_pth_import.cpp src/pth_import.cpp)
  add_executable(test_ring tests/test_ring.cpp)
  target_link_libraries(test_ring Threads::Threads)
  add_executable(test_session tests/test_session.cpp src/voice_session.cpp)
  target_link_libraries(test_session Threads::Threads)

  if(ORT_HOST_DIR)
    add_executable(offline_run tools/offline_run.cpp ${CORE_SRC})
    target_include_directories(offline_run PRIVATE ${ORT_HOST_DIR}/include src)
    target_link_directories(offline_run PRIVATE ${ORT_HOST_DIR}/lib)
    target_link_libraries(offline_run onnxruntime Threads::Threads)
    set_target_properties(offline_run PROPERTIES BUILD_RPATH ${ORT_HOST_DIR}/lib)
  endif()

  enable_testing()
  add_test(NAME ring COMMAND test_ring)
  add_test(NAME session COMMAND test_session)
  add_test(NAME dsp COMMAND test_dsp ${CMAKE_BINARY_DIR}/vectors)
endif()
