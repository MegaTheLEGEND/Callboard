#!/usr/bin/env bash
# Downloads the ONNX Runtime + QNN Android AAR from Maven Central and unpacks
#   engine/third_party/ort/headers/          C/C++ headers (for CMake)
#   engine/third_party/ort/jni/arm64-v8a/    libonnxruntime.so (for CMake linking)
#   android/app/src/main/jniLibs/arm64-v8a/  libonnxruntime.so + the QNN/HTP runtime libs (packaged in the APK)
#
# Override the version with:  ORT_QNN_VERSION=1.29.0 scripts/fetch_ort.sh
set -euo pipefail
VER="${ORT_QNN_VERSION:-1.27.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/${VER}/onnxruntime-android-qnn-${VER}.aar"

echo "==> downloading $URL"
curl -fL --retry 3 -o "$TMP/ort.aar" "$URL"
unzip -q "$TMP/ort.aar" -d "$TMP/aar"

echo "==> AAR contents (jni/arm64-v8a):"
ls -la "$TMP/aar/jni/arm64-v8a" || { echo "no arm64-v8a libs in AAR"; exit 1; }

HDR_SRC="$TMP/aar/headers"
[ -f "$HDR_SRC/onnxruntime_cxx_api.h" ] || HDR_SRC="$(dirname "$(find "$TMP/aar" -name onnxruntime_cxx_api.h | head -1)")"
[ -f "$HDR_SRC/onnxruntime_cxx_api.h" ] || { echo "headers not found in AAR"; exit 1; }

rm -rf "$ROOT/engine/third_party/ort"
mkdir -p "$ROOT/engine/third_party/ort/headers" "$ROOT/engine/third_party/ort/jni/arm64-v8a"
cp "$HDR_SRC"/*.h "$ROOT/engine/third_party/ort/headers/"
cp "$TMP/aar/jni/arm64-v8a/libonnxruntime.so" "$ROOT/engine/third_party/ort/jni/arm64-v8a/"

# Runtime libs (libonnxruntime.so + libQnnHtp.so + Hexagon skels) are packaged by Gradle from the
# onnxruntime-android-qnn / qnn-runtime dependencies. Remove any copy left by an older version of this script.
rm -rf "$ROOT/android/app/src/main/jniLibs"
echo "==> headers + link library ready in engine/third_party/ort (ORT QNN ${VER})"
echo "NOTE: the QNN/HTP libraries come from Gradle (qnn-runtime) at build time."
