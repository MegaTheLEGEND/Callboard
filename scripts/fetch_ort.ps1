# Windows version of fetch_ort.sh. Run from the rvc-s25 folder:
#   powershell -ExecutionPolicy Bypass -File scripts\fetch_ort.ps1
param([string]$Version = "1.27.0")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tmp  = Join-Path $env:TEMP "ort_qnn_aar"
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $tmp | Out-Null

$url = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/$Version/onnxruntime-android-qnn-$Version.aar"
Write-Host "==> downloading $url"
Invoke-WebRequest $url -OutFile "$tmp\ort.zip"          # an .aar is just a zip
Expand-Archive "$tmp\ort.zip" -DestinationPath "$tmp\aar"

$abi = "$tmp\aar\jni\arm64-v8a"
if (-not (Test-Path $abi)) { throw "no arm64-v8a libs in the AAR" }
Write-Host "==> AAR contents (jni/arm64-v8a):"
Get-ChildItem $abi | ForEach-Object { "{0,12:N0}  {1}" -f $_.Length, $_.Name }

$hdr = Get-ChildItem "$tmp\aar" -Recurse -Filter onnxruntime_cxx_api.h | Select-Object -First 1
if (-not $hdr) { throw "headers not found in the AAR" }

$ortDir = "$root\engine\third_party\ort"
Remove-Item $ortDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$ortDir\headers", "$ortDir\jni\arm64-v8a" | Out-Null
Copy-Item "$($hdr.DirectoryName)\*.h" "$ortDir\headers"
Copy-Item "$abi\libonnxruntime.so" "$ortDir\jni\arm64-v8a"

# Runtime libs (libonnxruntime.so + libQnnHtp.so + Hexagon skels) are packaged by Gradle from the
# onnxruntime-android-qnn / qnn-runtime dependencies. Remove any copy left by an older version of this script.
Remove-Item "$root\android\app\src\main\jniLibs" -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "==> headers + link library ready in engine\third_party\ort"
Write-Host "==> done (ORT QNN $Version). The QNN/HTP libraries come from Gradle (qnn-runtime) at build time."
