package dev.callboard

import android.content.Context
import com.rvcs25.app.Settings
import dev.callboard.rvc.NativeBridge
import java.io.File

/** Loads the RVC model pack and owns the user-tunable voice settings (persisted). */
object VoiceEngine {
    @Volatile var loaded = false; private set
    @Volatile var loading = false; private set
    @Volatile var message = ""; private set

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("voice", Context.MODE_PRIVATE)

    /** -12..+12 semitones */
    var pitch: Int get() = prefs.getInt("pitch", 0); set(v) = prefs.edit().putInt("pitch", v).apply()
    /** 0..100 %. Starts at 0 (retrieval off) until the stats show the phone keeps up with it. */
    var index: Int get() = prefs.getInt("index", 0); set(v) = prefs.edit().putInt("index", v).apply()
    /** 0 = off, else -(60 - v) dB */
    var gate: Int get() = prefs.getInt("gate", 0); set(v) = prefs.edit().putInt("gate", v).apply()
    /** Output safety buffer in ms */
    var slackMs: Int get() = prefs.getInt("slack", 40); set(v) = prefs.edit().putInt("slack", v).apply()
    var synthNoise: Boolean get() = prefs.getBoolean("noise", true); set(v) = prefs.edit().putBoolean("noise", v).apply()
    var sustained: Boolean get() = prefs.getBoolean("sustained", false); set(v) = prefs.edit().putBoolean("sustained", v).apply()
    var cpuBackend: Boolean get() = prefs.getBoolean("cpu", false); set(v) = prefs.edit().putBoolean("cpu", v).apply()
    var strictNpu: Boolean get() = prefs.getBoolean("strict", false); set(v) = prefs.edit().putBoolean("strict", v).apply()

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** adb push pack /sdcard/Android/data/<applicationId>/files/pack */
    fun packDir() = File(app.getExternalFilesDir(null), "pack")
    fun packPresent() = File(packDir(), "engine.cfg").exists()
    fun packHint() = "adb push pack /sdcard/Android/data/${app.packageName}/files/pack"

    fun gateDb(): Float = if (gate == 0) -60f else -(60f - gate)

    fun pushParams() {
        if (!loaded) return
        NativeBridge.nativeSetParams(pitch.toFloat(), index / 100f, gateDb(), synthNoise)
        NativeBridge.nativeSetCleanup(Settings.voiceIsolation, Settings.pitchStabilize)
    }

    /** Slow (minutes the first time: the NPU graphs are compiled once and cached). Runs on its own thread. */
    fun load(onDone: (String) -> Unit) {
        if (loading) return
        if (!packPresent()) { onDone("Voice model not found.\n${packHint()}"); return }
        loading = true; loaded = false
        message = "Loading… the first load compiles the model for the NPU and can take a few minutes."
        Thread({
            val ctxDir = File(app.filesDir, "htp_ctx").apply { mkdirs() }
            val err = try {
                NativeBridge.nativeCreate(
                    packDir().absolutePath, ctxDir.absolutePath, app.applicationInfo.nativeLibraryDir,
                    if (sustained) "sustained_high_performance" else "burst", strictNpu, cpuBackend, false,
                )
            } catch (t: Throwable) { "native engine failed to load: ${t.message}" }
            loaded = err.isEmpty()
            loading = false
            message = if (loaded) "Voice model ready." else "Load failed:\n$err"
            if (loaded) pushParams()
            onDone(message)
        }, "voice-load").start()
    }
}
