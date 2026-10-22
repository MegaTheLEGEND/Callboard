package dev.callboard.rvc

/** JNI surface of librvcengine (app/src/main/cpp/src/jni_bridge.cpp). */
object NativeBridge {
    init { System.loadLibrary("rvcengine") }

    /** Loads the model pack. Slow (minutes on the very first run: HTP graphs are compiled and cached). "" = ok. */
    external fun nativeCreate(
        packDir: String, ctxDir: String, nativeLibDir: String, perfMode: String,
        strictNpu: Boolean, cpuBackend: Boolean, verbose: Boolean,
    ): String

    /** "ok|version|sr|speakers|f0|reason" (reason non-empty = unsupported) or "err|message". */
    external fun nativeInspectPth(pth: String): String
    /** Converts a .pth for the given template folder into weights.bin + engine.cfg in [outDir]. "" = ok. */
    external fun nativeImportPth(pth: String, templateDir: String, outDir: String, sid: Int): String
    /** Writes [outOnnx] = the template synth with this voice's weights (from [weightsBin]) patched in. "" = ok. */
    external fun nativeBakeSynth(templateOnnx: String, weightsBin: String, outOnnx: String): String

    /** [0] samples per engine block at 48 kHz, [1] output lookahead in samples (offline alignment). */
    external fun nativeInfo(): IntArray

    // ---- standalone test: Oboe mic -> voice -> Oboe speaker --------------------------------------
    external fun nativeStart(inputDevice: Int, outputDevice: Int, slackMs: Int, unprocessedMic: Boolean): String

    // ---- live call ---------------------------------------------------------------------------------
    /** Starts the inference thread. Mic comes from [nativePushMic] or [nativeStartAppMic]; audio leaves via [nativePullUplink]. */
    external fun nativeStartLive(slackMs: Int, uplinkRate: Int): String
    external fun nativeStartAppMic(inputDevice: Int, unprocessedMic: Boolean): String
    /** [n] mono PCM16 samples at 48 kHz. Single producer thread. */
    external fun nativePushMic(pcm: ShortArray, n: Int)
    /** Fills [out] with [n] PCM16 samples at the uplink rate; call every 20 ms. Single consumer thread. */
    external fun nativePullUplink(out: ShortArray, n: Int): Int

    external fun nativeStop()
    external fun nativeRelease()

    // ---- offline conversion ------------------------------------------------------------------------
    /** Block size in samples (48 kHz), or -1 if not ready / a live session is running. */
    external fun nativeConvertBegin(): Int
    external fun nativeConvertBlock(input: ShortArray, output: ShortArray): Boolean

    external fun nativeSetParams(pitchSemitones: Float, indexRate: Float, gateDb: Float, synthNoise: Boolean)

    /** See jni_bridge.cpp: [0] total ms ... [18] mic peak 0..1 since the last call, [19] session running. */
    external fun nativeStats(): FloatArray
}
