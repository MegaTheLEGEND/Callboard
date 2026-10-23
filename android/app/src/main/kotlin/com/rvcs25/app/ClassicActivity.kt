package com.rvcs25.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.callboard.ShizukuLink

/**
 * Classic voice changer (modulation): presets plus a custom one. Opened from the "Voice changer" chooser on the home
 * screen. It runs the mic through the effects in [ClassicFx] and never at the same time as RVC.
 */
class ClassicActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var btnLive: Button
    private lateinit var btnTest: Button
    private lateinit var customBox: LinearLayout
    private val presetRows = ArrayList<LinearLayout>()
    private var note = "Pick a voice, then start. Use “Test with headphones” to hear it first."

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Cb.TEXT); setPadding(0, dp(10), 0, 0)
    }

    private fun slider(max: Int, init: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = init
        progressTintList = ColorStateList.valueOf(Cb.ACCENT); thumbTintList = ColorStateList.valueOf(Cb.ACCENT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(ColorDrawable(Cb.BG))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(24))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply {
                text = "‹ CallBoard"; textSize = 15f; setTextColor(Cb.ACCENT); setPadding(0, 0, 0, dp(8))
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "Settings ›"; textSize = 15f; setTextColor(Cb.ACCENT); setPadding(0, 0, 0, dp(8))
                setOnClickListener { startActivity(android.content.Intent(this@ClassicActivity, SettingsActivity::class.java)) }
            })
        })
        root.addView(TextView(this).apply { text = "Classic voice changer"; textSize = 22f; setTextColor(Cb.TEXT) })
        root.addView(TextView(this).apply {
            text = "Modulation effects, no AI model needed. It can't run at the same time as RVC."
            textSize = 12f; setTextColor(Cb.MUTED); setPadding(0, dp(4), 0, dp(10))
        })

        // ---- presets + custom
        val names = ClassicPresets.ALL.map { it.name to it.blurb } + ("Custom" to "Build your own: pitch, robot, distortion, echo, telephone")
        for ((i, nb) in names.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true; isFocusable = true
                setOnClickListener { select(i) }
            }
            row.addView(TextView(this).apply {
                text = nb.first; textSize = 16f; setTextColor(Cb.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            row.addView(TextView(this).apply { text = nb.second; textSize = 12f; setTextColor(Cb.MUTED) })
            presetRows += row
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) })
        }

        // ---- custom sliders (shown only while Custom is selected)
        customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val lPitch = label(""); val lRing = label(""); val lHz = label(""); val lDist = label(""); val lEcho = label("")
        fun upd() {
            lPitch.text = "Pitch: %+d semitones".format(Settings.cPitch)
            lRing.text = "Robot (ring modulation): ${Settings.cRingMix}%"
            lHz.text = "Robot tone: ${Settings.cRingHz} Hz  (low = robot, high = alien)"
            lDist.text = "Distortion: ${Settings.cDist}%"
            lEcho.text = "Echo: ${Settings.cEcho}%"
        }
        fun changed() { upd(); ClassicPresets.refresh() }
        customBox.addView(lPitch); customBox.addView(slider(24, Settings.cPitch + 12) { Settings.cPitch = it - 12; changed() })
        customBox.addView(lRing); customBox.addView(slider(100, Settings.cRingMix) { Settings.cRingMix = it; changed() })
        customBox.addView(lHz); customBox.addView(slider(480, Settings.cRingHz - 20) { Settings.cRingHz = it + 20; changed() })
        customBox.addView(lDist); customBox.addView(slider(100, Settings.cDist) { Settings.cDist = it; changed() })
        customBox.addView(lEcho); customBox.addView(slider(100, Settings.cEcho) { Settings.cEcho = it; changed() })
        val phone = Button(this).apply {
            isAllCaps = false
            fun t() { text = "Telephone filter: " + if (Settings.cPhone) "ON" else "OFF" }
            t()
            setOnClickListener { Settings.cPhone = !Settings.cPhone; t(); ClassicPresets.refresh() }
        }
        customBox.addView(phone)
        upd()
        root.addView(customBox)

        // ---- mic gain (shared with RVC)
        val lGain = label("")
        fun gainText() { lGain.text = "Mic gain: %+d dB".format(Settings.micGainDb) }
        gainText()
        root.addView(lGain)
        root.addView(slider(36, Settings.micGainDb + 12) { Settings.micGainDb = it - 12; gainText() })

        // ---- start buttons
        btnLive = Button(this).apply { text = "Start live call"; isAllCaps = false; setOnClickListener { toggleLive() } }
        btnTest = Button(this).apply { text = "Test with headphones"; isAllCaps = false; setOnClickListener { toggleTest() } }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0)
            addView(btnLive, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnTest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(Cb.TEXT); setPadding(0, dp(10), 0, 0) }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "Live call needs the Shizuku helper and an already-connected call. The far end hears only the changed voice."
            textSize = 11f; setTextColor(Cb.MUTED); setPadding(0, dp(8), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Cb.BG); addView(root) })
        ClassicPresets.refresh()
        styleRows()
        requestPerms()
        ui.post(poll)
    }

    private fun select(i: Int) {
        Settings.classicPreset = i
        ClassicPresets.refresh()          // a running session picks the change up on its next 20 ms frame
        styleRows()
    }

    private fun styleRows() {
        val sel = ClassicPresets.selected()
        for ((i, row) in presetRows.withIndex()) {
            row.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (i == sel) { setColor(Cb.blend(Cb.SURFACE, Cb.ACCENT, 0.16f)); setStroke(dp(1), Cb.ACCENT) }
                else setColor(Cb.SURFACE2)
            }
        }
        customBox.visibility = if (sel == ClassicPresets.CUSTOM) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------- actions
    private fun requestPerms() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (AudioInputs.btPermissionMissing(this)) need += Manifest.permission.BLUETOOTH_CONNECT
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun say(msg: String) { VoiceService.lastError = ""; note = msg }

    private fun toggleLive() {
        if (VoiceService.classicRunning) { if (VoiceService.mode == "classic") VoiceService.stop(this); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPerms(); return }
        if (RvcApp.link.state != ShizukuLink.State.CONNECTED) {
            say("Shizuku helper not connected. Start Shizuku, then tap its chip on the home screen."); return
        }
        ClassicPresets.refresh()
        LiveVoice.method = Settings.method
        LiveVoice.micSource = Settings.micSource
        LiveVoice.keepUnmuted = Settings.keepUnmuted
        // Same rule as RVC: the helper (Shizuku) mic is only used when the setting is on. With a Bluetooth / external
        // mic chosen, Android silences every phone mic during the call, so the app must capture from that device.
        val why = VoiceService.startClassicLive(this, if (Settings.hd16k) 16000 else 8000, Settings.shellMic, Settings.rawMic)
        say(if (why.isNotEmpty()) why else "Starting live call audio… the call must already be connected.")
    }

    private fun toggleTest() {
        if (VoiceService.classicRunning) { if (VoiceService.mode == "classic_test") VoiceService.stop(this); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPerms(); return }
        val why = VoiceService.startClassicTest(this, Settings.rawMic)
        say(if (why.isNotEmpty()) why else "Starting… use headphones to avoid feedback.")
    }

    // --------------------------------------------------------------- status
    private val poll = object : Runnable {
        override fun run() {
            val running = VoiceService.running
            val mode = VoiceService.mode
            btnLive.text = if (running && mode == "classic") "Stop live" else "Start live call"
            btnTest.text = if (running && mode == "classic_test") "Stop test" else "Test with headphones"
            btnLive.isEnabled = !running || mode == "classic"
            btnTest.isEnabled = !running || mode == "classic_test"
            status.text = when {
                VoiceService.rvcRunning -> "RVC is running. Stop it before using the classic voice changer."
                running && mode == "classic" -> liveStatus()
                running && mode == "classic_test" -> "Headphone test is running: ${ClassicPresets.label()}. Use headphones to avoid feedback."
                !running && VoiceService.lastError.isNotEmpty() -> "Audio error: ${VoiceService.lastError}"
                else -> note
            }
            ui.postDelayed(this, 300)
        }
    }

    /** Live status with a mic / call level readout, so a silent mic is obvious. */
    private fun liveStatus(): String {
        val i = LiveVoice.inPeak; val o = LiveVoice.outPeak
        LiveVoice.inPeak = 0; LiveVoice.outPeak = 0
        shownIn = maxOf(i, shownIn * 3 / 4); shownOut = maxOf(o, shownOut * 3 / 4)   // slow fall-off so it is readable
        return buildString {
            append("Live call audio is running: ${ClassicPresets.label()}.")
            if (LiveVoice.micMode.isNotEmpty()) append("\nmic: ${LiveVoice.micMode}")
            append("\nlevels: mic in %d%% -> call out %d%%".format(shownIn * 100 / 32768, shownOut * 100 / 32768))
            if (shownIn < 40) append("\nThe microphone looks silent.")
            if (LiveVoice.micNote.isNotEmpty()) append("\n${LiveVoice.micNote}")
        }
    }

    private var shownIn = 0
    private var shownOut = 0

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        super.onDestroy()
    }
}
