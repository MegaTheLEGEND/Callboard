package com.rvcs25.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.app.Activity
import dev.callboard.ShizukuLink
import dev.callboard.VoiceEngine
import dev.callboard.rvc.NativeBridge
import java.io.File

/**
 * Single-screen control panel. UI is built in code to keep the project dependency-free.
 *
 * Model pack location (push it with adb, see README):
 *   /sdcard/Android/data/com.rvcs25.app/files/pack/
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var btnLoad: Button
    private lateinit var btnRun: Button
    private lateinit var btnLive: Button
    private lateinit var btnGrant: Button
    private lateinit var btnMon: Button
    private lateinit var helper: TextView
    private lateinit var skPitch: SeekBar
    private lateinit var skIndex: SeekBar
    private lateinit var skGain: SeekBar
    private lateinit var lblGain: TextView
    private lateinit var lblPitch: TextView
    private lateinit var lblIndex: TextView

    private var loaded = false
    private var loading = false
    private var loadedSig = ""
    private var loadedVoice = ""
    private lateinit var voiceLbl: TextView

    private val packDir get() = Voices.active()?.dir ?: File(getExternalFilesDir(null), "pack")
    private val ctxDir get() = File(filesDir, "htp_ctx").apply { mkdirs() }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setPadding(0, dp(10), 0, 0)
    }

    private fun slider(max: Int, init: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = init
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(24))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "RVC • Galaxy S25 Ultra"; textSize = 22f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Settings"; isAllCaps = false
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            })
        })
        voiceLbl = TextView(this).apply { textSize = 15f; setPadding(0, dp(6), 0, 0) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(voiceLbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Voices / add"; isAllCaps = false
                setOnClickListener { startActivity(Intent(this@MainActivity, VoicesActivity::class.java)) }
            })
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(status)

        btnLoad = Button(this).apply { text = "Load model"; setOnClickListener { loadModel() } }
        btnRun = Button(this).apply { text = "Start"; isEnabled = false; setOnClickListener { toggleRun() } }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnLoad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnRun, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        btnGrant = Button(this).apply { text = "Grant Shizuku"; setOnClickListener { RvcApp.link.requestPermission() } }
        btnLive = Button(this).apply { text = "Start live call"; isEnabled = false; setOnClickListener { toggleLive() } }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnGrant, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnLive, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        btnMon = Button(this).apply {
            text = "Monitor mic input (RVC muted): OFF"
            setOnClickListener {
                VoiceEngine.monitorInput = !VoiceEngine.monitorInput
                text = "Monitor mic input (RVC muted): " + if (VoiceEngine.monitorInput) "ON" else "OFF"
                VoiceEngine.pushParams()
            }
        }
        root.addView(btnMon)
        helper = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setPadding(0, dp(6), 0, 0)
        }
        root.addView(helper)

        // Quick controls; both are saved, so they come back the way you left them.
        lblPitch = label(""); skPitch = slider(24, Settings.pitch + 12) { Settings.pitch = it - 12; updateLabels(); pushParams() }
        lblIndex = label(""); skIndex = slider(100, Settings.indexRate) { Settings.indexRate = it; updateLabels(); pushParams() }
        lblGain = label(""); skGain = slider(36, Settings.micGainDb + 12) { Settings.micGainDb = it - 12; updateLabels(); pushParams() }
        listOf(lblPitch, skPitch, lblIndex, skIndex, lblGain, skGain).forEach { root.addView(it) }

        stats = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; setPadding(0, dp(14), 0, 0)
        }
        root.addView(stats)
        setContentView(ScrollView(this).apply { addView(root) })
        updateLabels()
        requestPerms()
        refreshStatus()
        ui.post(poll)
        ui.post(helperPoll)
    }

    private fun updateLabels() {
        lblPitch.text = "Pitch: %+d semitones".format(skPitch.progress - 12)
        lblIndex.text = "Index rate (voice similarity): ${skIndex.progress}%"
        val gdb = skGain.progress - 12
        lblGain.text = "Mic gain: %+d dB  (×%.1f)".format(gdb, Math.pow(10.0, gdb / 20.0))
    }

    private fun pushParams() {
        if (!loaded) return
        NativeBridge.nativeSetParams(
            Settings.pitch.toFloat(), Settings.indexRate / 100f, Settings.gateDb, Settings.synthNoise,
        )
        NativeBridge.nativeSetMicGain(Settings.micGainDb.toFloat())
        NativeBridge.nativeSetCleanup(Settings.voiceIsolation, Settings.pitchStabilize)
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed on the settings page.
        pushParams()
        val v = Voices.active()
        voiceLbl.text = "Voice: " + (v?.label ?: "none yet")
        if (loaded && !loading && loadedVoice != (v?.id ?: ""))
            status.text = "Voice changed. Tap “Load model” to switch (a new voice compiles for the NPU once)."
        else if (loaded && !loading && loadedSig != Settings.loadSignature)
            status.text = "Performance settings changed. Tap “Load model” to apply them."
        if (!loaded && !loading) refreshStatus()
    }

    // ------------------------------------------------------------- actions
    private fun requestPerms() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (AudioInputs.btPermissionMissing(this)) need += Manifest.permission.BLUETOOTH_CONNECT   // Bluetooth mic selected
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun loadModel() {
        if (loading) return
        val vid = Voices.active()?.id ?: ""
        if (!File(packDir, "engine.cfg").exists()) {
            status.text = "Step 1: tap “Voices” and add a voice (.pth)."
            return
        }
        loading = true; loaded = false
        btnLoad.isEnabled = false; btnRun.isEnabled = false; btnLive.isEnabled = false
        status.text = "Loading… the first load compiles the model for the NPU and can take a few minutes. Later loads take seconds."
        val perf = if (Settings.sustained) "sustained_high_performance" else "burst"
        val cpu = Settings.cpuBackend; val strict = Settings.strictNpu
        val sig = Settings.loadSignature
        Thread {
            val err = NativeBridge.nativeCreate(
                packDir.absolutePath, ctxDir.absolutePath, applicationInfo.nativeLibraryDir, perf, strict, cpu, false,
            )
            ui.post {
                loading = false
                loaded = err.isEmpty()
                if (loaded) { loadedSig = sig; loadedVoice = vid }
                btnLoad.isEnabled = true
                btnRun.isEnabled = loaded
                btnLive.isEnabled = loaded
                status.text = if (loaded) "Model ready." else "Load failed:\n$err"
                if (loaded) pushParams()
            }
        }.start()
    }

    private fun toggleRun() {
        if (!loaded) return
        if (VoiceService.running) {
            VoiceService.stop(this)
            btnRun.text = "Start"
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPerms(); return
            }
            pushParams()
            VoiceService.start(this, Settings.slack, Settings.rawMic)
            btnRun.text = "Stop"
            status.text = "Starting audio… use headphones to avoid feedback."
        }
    }

    private fun toggleLive() {
        if (!loaded) return
        if (VoiceService.running) {
            if (VoiceService.mode == "live") VoiceService.stop(this)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPerms(); return
        }
        if (RvcApp.link.state != ShizukuLink.State.CONNECTED) {
            status.text = "Shizuku helper not connected. Start Shizuku, then tap Grant Shizuku."
            return
        }
        pushParams()
        LiveVoice.method = Settings.method
        LiveVoice.micSource = Settings.micSource
        LiveVoice.keepUnmuted = Settings.keepUnmuted
        VoiceService.startLive(this, Settings.slack, if (Settings.hd16k) 16000 else 8000, Settings.shellMic, Settings.rawMic)
        status.text = "Starting live call audio… the call must already be connected." +
            if (Settings.wifiOff && Startup.wifiOn(this)) "\nNote: Wi-Fi is on, but your settings say it should be off." else ""
    }

    private fun refreshStatus() {
        if (!File(packDir, "engine.cfg").exists())
            status.text = "Step 1: tap “Voices” and add a voice (.pth)."
        else if (!loaded) status.text = "Step 2: tap “Load model”, then Start."
    }

    // --------------------------------------------------------------- stats
    private val poll = object : Runnable {
        override fun run() {
            if (loaded) {
                val s = NativeBridge.nativeStats()
                if (s.size >= 18) {
                    val running = VoiceService.running
                    val mode = VoiceService.mode
                    btnRun.text = if (running && mode == "test") "Stop" else "Start"
                    btnLive.text = if (running && mode == "live") "Stop live" else "Start live call"
                    btnRun.isEnabled = !running || mode == "test"
                    btnLive.isEnabled = !running || mode == "live"
                    if (VoiceService.lastError.isNotEmpty() && !running) status.text = "Audio error: ${VoiceService.lastError}"
                    if (s[16] > 0f && running) {          // headset unplugged / route changed
                        VoiceService.stop(this@MainActivity)
                        status.text = "Audio device changed — press Start again."
                    }
                    val audioIn = if (s[13] >= 0) s[13] else 0f
                    val audioOut = if (s[14] >= 0) s[14] else 0f
                    val est = s[11] + s[0] + s[8] + audioIn + audioOut
                    stats.text = buildString {
                        append("latency  ≈ %4.0f ms  (algorithm %.0f + compute %.0f + buffer %.0f + audio %.0f)\n"
                            .format(est, s[11], s[0], s[8], audioIn + audioOut))
                        append("block    %4.0f ms budget, compute %.0f ms  [%s]\n"
                            .format(s[12], s[0], if (s[0] < s[12] * 0.8f) "OK" else if (s[0] < s[12]) "TIGHT" else "TOO SLOW"))
                        append("stages   f0||hubert %.0f | f0 post+retr %.0f | synth %.0f | resample+sola %.0f ms\n"
                            .format(s[1], s[3], s[4], s[5]))
                        append("health   underruns %d | dropped %d | exclusive audio: %s | NPU cache: %s\n"
                            .format(s[6].toInt(), s[7].toInt(), if (s[15] > 0) "yes" else "no", if (s[17] > 0) "hit" else "miss (first run)"))
                        if (s.size >= 25) {
                            val rate = Settings.indexRate; val n = s[21].toInt()
                            append("index    " + when {
                                s[20] <= 0f -> "none in this voice (add a .zip with the .pth and its .index)"
                                rate == 0 -> "loaded (%,d vectors) but Index rate is 0, so it is not used".format(n)
                                s[22] > 0f -> "ON · %,d vectors · rate %d%% · features shifted %.0f%% · %.1f ms".format(n, rate, s[23] * 100f, s[24])
                                else -> "ready · %,d vectors · rate %d%% (used once audio is running)".format(n, rate)
                            } + "\n")
                        }
                        if (s.size > 18) {
                            val dev = LiveVoice.micMode.ifEmpty { AudioInputs.active.ifEmpty { "-" } }
                            append("mic      peak %.2f%s  (%s)\n".format(s[18], if (s[18] >= 0.99f) "  CLIPPING: lower the gain" else "", dev))
                            if (AudioInputs.note.isNotEmpty()) append("         ${AudioInputs.note}\n")
                        }
                    }
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    // Helper status makes a Binder call, so it runs off the main thread.
    private val helperPoll = object : Runnable {
        override fun run() {
            Thread {
                Startup.runOnce(this@MainActivity)
                val t = RvcApp.link.describe()
                val mic = LiveVoice.micMode
                val note = Startup.note
                ui.post {
                    helper.text = t + (if (note.isEmpty()) "" else "\n$note") + (if (mic.isEmpty()) "" else "\nlive mic: $mic")
                }
            }.start()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        ui.removeCallbacks(helperPoll)
        super.onDestroy()
    }
}
