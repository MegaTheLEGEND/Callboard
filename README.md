# RVC S25 — realtime voice conversion on the Galaxy S25 Ultra (Snapdragon 8 Elite NPU)

A single-voice, static-shape port of [RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)
for realtime mic → converted-voice playback, running HuBERT, RMVPE pitch and the vocoder on the
Hexagon NPU through ONNX Runtime's QNN execution provider.

```
mic 48k ─► resample 16k ─┬─► RMVPE mel (CPU) ─► RMVPE net (NPU/CPU) ─► decode/interp/coarse (CPU) ─┐
                         └─► HuBERT (NPU) ─► index blend (CPU, NEON) ────────────────────────┤
                                                                                            ▼
                                      sine excitation + noise (CPU) ─► synth: enc/flow/NSF-HiFiGAN (NPU)
                                                                                            ▼
                                                 resample 40k→48k ─► SOLA crossfade ─► speaker
```
Default timing: 100 ms blocks, 500 ms context, algorithmic latency floor 150 ms + compute + audio path.

## Status — read this first

| Part | State |
|---|---|
| Model export (synth / FCPE / HuBERT / index) | **Verified** on your `model.pth`/`model.index`: synth matches original RVC `infer()` to 2e-6; FCPE matches torchfcpe to 1e-6; all graphs static-shape, all ops in the QNN-EP supported list |
| HuBERT weights | **Not verified with real weights** (not downloadable where I built this). Architecture + export verified with random init; you must supply `hubert_base` |
| C++ DSP (resamplers, SOLA, sine source, pitch, retrieval, rings, session logic) | **Unit-tested** on host (x86) against Python references of the original RVC logic; NEON kernel validated via SIMDe |
| Whole C++ engine | **Cross-checked** against an independent Python pipeline: 74 dB SNR, 60 blocks, two parameter sets |
| Real-time session logic | **Tested** with a simulated audio clock (jitter, underrun adaptation, overload) |
| QNN/HTP execution, Oboe audio, JNI, Kotlin app, Gradle build | **Written but NEVER COMPILED OR RUN** — I had no Android SDK/NDK or device. Expect to fix a few build errors and tune on-device |
| NPU speed / latency / thermals | **Unmeasured.** Some ops may fall back to CPU (see Troubleshooting) |

## 1. Build the model pack (PC, Python)

```bash
pip install -r export/requirements.txt
# HuBERT (Transformers format), per the RVC README:
huggingface-cli download lj1995/VoiceConversionWebUI --include "hubert_base/*" --local-dir assets

python export/build_pack.py --pth model.pth --index model.index \
       --hubert-dir assets/hubert_base --out pack/
```
Knobs: `--block 10` (×10 ms of new audio per step; try 8 or 12), `--extra 50` (context), `--crossfade 5`,
`--sid 0` (speaker baked in), `--index-size 16384` (k-means-shrink the index; default keeps all vectors).
`--random-init` builds a *test-only* pack with meaningless HuBERT features.
The pack is ~600 MB (FP32 weights; the NPU runs them in FP16).

## 2. Build the app

1. `scripts/fetch_ort.sh` — downloads the ONNX Runtime + QNN AAR (`ORT_QNN_VERSION=...` to change) and unpacks headers + libs.
   Check its output lists `libQnnHtp.so` **and `libQnnHtpV79Skel.so`** (Hexagon v79 = Snapdragon 8 Elite).
2. Open `android/` in Android Studio (Ladybug+; installs SDK/NDK/CMake on demand) or run `cd android && ./gradlew assembleDebug`.
3. Install on the phone (USB debugging on).

## 3. Run

```bash
adb push pack /sdcard/Android/data/com.rvcs25.app/files/pack
```
Open the app → **Load model** (first load compiles the graphs for the NPU: minutes; cached afterwards) → **Start**.
Use wired/USB headphones for the lowest latency (Bluetooth adds 100+ ms) and to avoid feedback.

### Adding voices from the app (.pth, v1 and v2) — fully on the phone

**Base models + APK — no PC needed.** Push this project to GitHub and run *Actions → Build base models + APK*
(`.github/workflows/build-base.yml`; ~1-2 h, free). It exports HuBERT v1/v2, FCPE and a synth template per variant, **bakes
the ones listed in the `bake` input (default v2_40000 + v1_40000) into the APK**, builds the APK, and publishes everything as a Release.
Install `app-debug.apk` from the release (the workflow's `build_type` input defaults to *debug*; choose *release* to get `RvcS25.apk` instead). The debug build is debuggable, so `adb shell run-as com.rvcs25.app ls files/voices` works. (it is ~1 GB with the defaults). Voices of those types work offline immediately; any other
type is fetched on demand from the same release: in the app, *Voices → Base models*, enter `owner/repo`.
**Updating without reinstalling.** Every build (local or CI, debug or release) is signed with the key in
`android/signing/rvcs25.jks` and gets an ever-increasing `versionCode`, so a new APK installs **over** the old one and keeps
your voices and settings: just open the new APK (or `adb install -r app-debug.apk`). Only the very first install after this
change needs an uninstall (the old builds used a different, random key). Debug and release builds also install over each other.
The keystore is committed on purpose (personal sideload app; password `rvcs25app`, alias `rvcs25`). If the repo is public and you
don't want others to be able to sign "updates" for your install, replace it with your own key via the optional secrets
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (and use that key for every build from then on).
Prefer doing it by hand? `python export/build_base.py --hubert-dir assets/hubert_base --out base/` and
`adb push base /sdcard/Android/data/com.rvcs25.app/files/base` works too (variants: `--variants v2_48000 ...`, `--from-pth model.pth`).
The script self-tests each template (random weights injected by name must reproduce PyTorch).

**Then, on the phone:** *Voices* → paste a link (a Hugging Face file link, a direct `.pth`, or a `.zip` containing one)
or choose a `.pth` from storage → **Download & add** → back on the main screen **Load model**. The app reads the `.pth`
itself (no pickle code is run), folds weight-norm, picks speaker 0 and hands the weights to the template; HuBERT/FCPE
are shared and compiled once, only the new voice's synth compiles for the NPU (minutes, first load only).
Downloads need mobile data or Wi-Fi (the app's “Wi-Fi off at startup” option can be switched off in Settings).
**Index (retrieval).** Add a `.zip` that holds the model and its index (`name.zip` = `model.pth` + `model.index`) by link or
from the phone: the voice is named after the zip, and the faiss `added_IVF…_Flat….index` is converted on the phone to the
engine's `index.bin` (`engine/src/index_import.cpp`, no faiss needed) so the Index rate slider works for it. A `trained_…` index
is ignored (it holds no vectors). If the index can't be used (wrong model, unsupported type) the voice is still added without it
and the reason is shown. A bare `.pth` still imports without an index.

Limits: f0 (pitch-guided) voices only; voices whose architecture differs from the installed template are rejected with a message.

### Settings and startup defaults

Tap **Settings** on the main screen. Everything is grouped, explained, and saved automatically between launches
(`Settings.kt`). Out of the box the app starts with:

| Setting | Startup default |
|---|---|
| Turn Wi-Fi off at startup (via the Shizuku helper, once per app start) | on |
| Capture mic through the Shizuku helper | off |
| Force the call un-muted | off |
| Index rate | 0 |
| Audio path | Direct |
| Helper mic source | Auto |

*Reset all settings* restores these. Settings marked "apply on next Load model" (sustained mode, CPU/strict debug) only
take effect when you reload the model; the main screen tells you when a reload is needed.

## Voice isolation and pitch stabiliser (Settings → Voice)

Both run inside the engine (`engine/src/cleanup.cpp`), are on by default (50 % / 60 %) and are live-tunable; 0 turns them off.

* **Voice isolation** — streaming spectral noise suppressor on the 16 kHz mic stream before HuBERT/FCPE: per-bin noise-floor
  tracking, Wiener gain, plus a speech gate (hold 150–250 ms) that fades the signal to near silence when nothing in 100 Hz–4 kHz
  stands above the noise floor. Handles steady noise (fans, hiss, road, hum). It does **not** separate a second talker or music
  (that needs a neural separator such as RNNoise/DeepFilterNet, which cannot be fetched or built here). Adds 20 ms while on.
  Android's own `NoiseSuppressor` / `VOICE_COMMUNICATION` source is still available via *Raw microphone* off and *Helper mic
  source*; it is device-dependent and stacking it with this one can warble, so keep *Raw microphone* on.
* **Pitch stabiliser** — post-processes the FCPE track before unvoiced interpolation: drops f0 outside 50–1100 Hz, folds octave
  errors, rejects isolated jumps (tolerance widens the longer pitch was missing), then a 3/5-point median and light smoothing.
  Tracks glides and normal intonation; very high values flatten expressive pitch.
* **Neural denoiser** — if `denoiser_model.onnx` (streaming DeepFilterNet-style, 480-sample frames @48 kHz) is in the voice/base folder, the
  *Voice isolation* slider drives it instead of the spectral suppressor (strength → max attenuation 6–60 dB, ≥95 % = unlimited). It runs on the
  CPU at 48 kHz before the gate/resampler (~3 ms per 10 ms frame on x86; adds roughly one STFT window of delay). Without the file the spectral one is used.
* **Pitch is RMVPE** (`rmvpe.pt`, RVC decode: threshold 0.03, zero-padded ±4-bin local average). Graph/file names stay `fcpe_*` for compatibility.

Host test: `cmake --build build && ctest -R cleanup`.

## Tuning

* The stats panel shows compute vs the block budget. `OK` means compute < 80 % of the block.
* Crackling → raise *Output safety buffer*, or rebuild with a larger `--block`.
* Too much delay → lower the safety buffer, rebuild with `--block 8`, lower `--extra` (less context, slightly worse quality).
* *Index rate* trades voice similarity vs articulation; 0 skips retrieval entirely. The stats panel shows whether it is really being used:
  `index ON · N vectors · rate R% · features shifted S% · T ms` (S is how far the retrieved neighbours moved the HuBERT features; 0% = no
  effect), or why not (`none in this voice`, `Index rate is 0`).
* *Mic gain* (main screen and Settings, −12…+24 dB) is applied to every mic source before the engine; the `mic peak` line is measured
  after the gain and flags clipping. *Input device* (Settings → Microphone) picks the phone mic, Bluetooth headphones / watch /
  hands-free kit, wired or USB mic by name; Bluetooth ones are routed through Android's communication device (needs the
  “Nearby devices” permission). A specific device uses the app mic path, not the Shizuku helper mic.

## Troubleshooting

* **Load fails with a QNN error**: confirm the skel lib is packaged, the app was installed with `extractNativeLibs=true`
  (already set), and check `adb logcat | grep -i -E "qnn|rvc|adsp"`.
* **Slow / not on NPU**: tick *Debug: fail if any op falls back to CPU*; the error lists the offending node. Likely
  suspects: HuBERT's positional-conv/GELU pattern, attention-mask `Where`/`Equal` leftovers in the synth. Fix by
  rewriting that op in `export/` (see `verify_synth.py` op audit) — all ops are in the documented QNN list, but
  per-shape/dtype support must be confirmed on device.
* **Context cache**: compiled HTP binaries live in the app's `files/htp_ctx/`; they auto-invalidate when the pack or
  ORT version changes.
* QNN packaging is changing: Microsoft's built-in QNN EP is being superseded by Qualcomm's plugin EP
  (`com.qualcomm.qti:onnxruntime-android-qnn`). All EP-specific code is in `engine/src/ort_graph.cpp`.

## Tests (host)

```bash
python export/gen_test_vectors.py build/vectors
cmake -S engine -B build -DORT_HOST_DIR=/path/to/onnxruntime-linux-x64 && cmake --build build && (cd build && ctest)
python export/reference_stream.py --pack pack --in in48k.wav --out ref.wav --blocks 60 --no-noise --index-rate 0.5
build/offline_run --pack pack --in in48k.wav --out eng.wav --no-noise --index-rate 0.5 && python export/compare_wavs.py ref.wav eng.wav
```

## Differences from stock RVC (by design)

* One voice, one fixed block size, f0 models only, no formant shift, no training.
* Retrieval is exact brute-force k-NN (stock RVC uses approximate IVF `nprobe=1`, so neighbours differ slightly).
* Synth noise and sine noise come from a fast RNG on the CPU (can be switched off for a deterministic output).

## Next ideas

FP16 weight files (halve the 600 MB pack) · ADPF performance-hint session for sustained clocks ·
INT8/INT16 QDQ quantisation calibrated on your voice · run HuBERT/FCPE on separate HTP contexts concurrently ·
a neural denoiser/separator for competing talkers · multiple baked voices.
