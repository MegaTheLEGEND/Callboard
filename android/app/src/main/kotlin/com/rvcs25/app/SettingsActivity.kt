package com.rvcs25.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * All options in one place, grouped and explained. Every change is saved immediately
 * (see [Settings]) and picked up by MainActivity when you come back.
 */
class SettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private var micRow: View? = null
    private var micValue: TextView? = null
    private var pathValue: TextView? = null
    private var micDevRow: View? = null
    private var micDevValue: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val dim = 0xFF9AA0A6.toInt()
    private val accent = 0xFF4FC3F7.toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "‹ Back"; textSize = 15f; setTextColor(accent); setPadding(0, 0, 0, dp(6))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply { text = "Settings"; textSize = 24f })
        root.addView(note("Changes are saved automatically and kept between launches."))

        // ---------------------------------------------------------------- Startup
        section("Startup")
        toggle(
            "Turn Wi-Fi off at startup",
            "Uses the Shizuku helper, once, when the app starts. Needs Shizuku running and granted.",
            Settings.wifiOff,
        ) { Settings.wifiOff = it }

        // ------------------------------------------------------------- Microphone
        section("Microphone")
        micDevRow = choice(
            "Input device",
            "Phone mic, Bluetooth headphones, a Bluetooth watch, a hands-free kit, wired or USB. Connect the device in Android’s " +
                "Bluetooth settings first, then pick it here. Applies on the next Start.",
            currentDeviceName(),
        ) { value -> micDevValue = value }.also { row -> row.setOnClickListener { pickInputDevice() } }
        slider(
            max = 36, init = Settings.micGainDb + 12,
            label = { "Mic gain: %+d dB  (×%.1f)".format(it - 12, Math.pow(10.0, (it - 12) / 20.0)) },
            help = "Boost a quiet mic (Bluetooth headsets are often quiet). Watch “mic peak” on the main screen: " +
                "1.00 means the signal is clipping, so turn it down.",
        ) { Settings.micGainDb = it - 12 }
        root.addView(note("A specific device uses the app’s own mic, not the Shizuku helper mic, because the helper can’t choose a device. " +
            "Bluetooth mics are narrow-band (8–16 kHz), so expect a duller input than the phone mic."))

        // -------------------------------------------------------------- Live call
        section("Live call")
        toggle(
            "Capture mic through the Shizuku helper",
            "Off = use the app’s own microphone (what you want for a Bluetooth mic; pick the device under Microphone).",
            Settings.shellMic,
        ) { Settings.shellMic = it; refreshMicRow() }

        micRow = choice(
            "Helper mic source",
            "Only used when helper capture is on.",
            currentMicName(),
        ) { value -> micValue = value }.also { row -> row.setOnClickListener { pickMicSource() } }

        pathRow()

        toggle(
            "Force the call un-muted",
            "Keeps your converted voice audible even if the call is muted in the dialer. Your real voice may be heard too.",
            Settings.keepUnmuted,
        ) { Settings.keepUnmuted = it }
        toggle(
            "16 kHz uplink",
            "Better quality, but only on HD-voice calls. Otherwise 8 kHz is used.",
            Settings.hd16k,
        ) { Settings.hd16k = it }

        // ------------------------------------------------------------------ Voice
        section("Voice")
        toggle(
            "Synth noise",
            "More natural sounding, slightly less clean.",
            Settings.synthNoise,
        ) { Settings.synthNoise = it }
        toggle(
            "Raw microphone",
            "Skips Android’s own voice processing (echo cancel, noise suppression).",
            Settings.rawMic,
        ) { Settings.rawMic = it }
        slider(
            max = 40, init = Settings.gate,
            label = { if (it == 0) "Noise gate: off" else "Noise gate: −${60 - it} dB" },
        ) { Settings.gate = it }
        slider(
            max = 100, init = Settings.slack,
            label = { "Output safety buffer: $it ms" },
            help = "Lower = less delay, but more risk of crackling.",
        ) { Settings.slack = it }
        root.addView(note("Pitch and index rate are on the main screen."))

        // ------------------------------------------------------------ Performance
        section("Performance (apply on next “Load model”)")
        toggle(
            "Sustained NPU mode",
            "Runs cooler over long sessions at slightly lower peak speed.",
            Settings.sustained,
        ) { Settings.sustained = it }
        toggle(
            "Debug: use CPU instead of NPU",
            "Much slower. For troubleshooting only.",
            Settings.cpuBackend,
        ) { Settings.cpuBackend = it }
        toggle(
            "Debug: fail if any op falls back to CPU",
            "Shows which model node is not running on the NPU.",
            Settings.strictNpu,
        ) { Settings.strictNpu = it }

        // ------------------------------------------------------------------ Reset
        root.addView(Button(this).apply {
            text = "Reset all settings to startup defaults"; isAllCaps = false
            setOnClickListener { confirmReset() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })
        root.addView(note(
            "Startup defaults: Wi-Fi off · helper mic capture off · force un-mute off · " +
                "index rate 0 · audio path Direct · helper mic source Auto."
        ))

        setContentView(ScrollView(this).apply { addView(root) })
        refreshMicRow()
    }

    // ------------------------------------------------------------------ builders
    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(dim); setPadding(0, dp(4), 0, 0)
    }

    private fun section(title: String) {
        root.addView(TextView(this).apply {
            text = title.uppercase(); textSize = 12f; setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            setPadding(0, dp(26), 0, dp(2))
        })
    }

    private fun toggle(title: String, help: String, on: Boolean, onChange: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            text = title; textSize = 15f; isChecked = on
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        root.addView(note(help))
    }

    /** A tappable row: title, current value, and a help line. [bind] receives the value TextView. */
    private fun choice(title: String, help: String, value: String, bind: (TextView) -> Unit): View {
        val v = TextView(this).apply { text = value; textSize = 14f; setTextColor(accent) }
        bind(v)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(2))
            addView(TextView(context).apply { text = title; textSize = 15f })
            addView(v)
            addView(note(help))
        }
        root.addView(row)
        return row
    }

    private fun slider(max: Int, init: Int, label: (Int) -> String, help: String? = null, onChange: (Int) -> Unit) {
        val l = TextView(this).apply { text = label(init); textSize = 15f; setPadding(0, dp(14), 0, 0) }
        root.addView(l)
        root.addView(SeekBar(this).apply {
            this.max = max; progress = init
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { l.text = label(p); onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        if (help != null) root.addView(note(help))
    }

    // ------------------------------------------------------------------- choices
    private fun currentMicName() = Settings.MIC_NAMES[Settings.MIC_IDS.indexOf(Settings.micSource).coerceAtLeast(0)]

    private fun currentDeviceName(): String {
        if (Settings.micDeviceKey.isEmpty()) return "Automatic (system default)"
        val d = AudioInputs.selected(this)
        return if (d != null) d.label else "${Settings.micDeviceName} (not connected, using the default)"
    }

    private fun pickInputDevice() {
        val devs = AudioInputs.list(this)
        val names = listOf("Automatic (system default)") + AudioInputs.labels(devs)
        val cur = AudioInputs.selected(this)?.let { s -> devs.indexOfFirst { it.key == s.key } + 1 } ?: 0
        AlertDialog.Builder(this).setTitle("Input device")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                AudioInputs.choose(if (which == 0) null else devs[which - 1])
                micDevValue?.text = currentDeviceName()
                if (AudioInputs.btPermissionMissing(this)) requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 7)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickMicSource() {
        val checked = Settings.MIC_IDS.indexOf(Settings.micSource).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Helper mic source")
            .setSingleChoiceItems(Settings.MIC_NAMES.toTypedArray(), checked) { d, which ->
                Settings.micSource = Settings.MIC_IDS[which]
                micValue?.text = currentMicName()
                d.dismiss()
            }.show()
    }

    private fun pathRow() {
        val row = choice(
            "Audio path",
            Settings.PATH_HELP[Settings.method],
            Settings.PATH_NAMES[Settings.method],
        ) { value -> pathValue = value }
        row.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Audio path")
                .setSingleChoiceItems(
                    Array(3) { "${Settings.PATH_NAMES[it]} — ${Settings.PATH_HELP[it]}" }, Settings.method,
                ) { d, which ->
                    Settings.method = which
                    pathValue?.text = Settings.PATH_NAMES[which]
                    // the help line is the last child of the row
                    ((row as LinearLayout).getChildAt(row.childCount - 1) as TextView).text = Settings.PATH_HELP[which]
                    d.dismiss()
                }.show()
        }
    }

    override fun onResume() { super.onResume(); micDevValue?.text = currentDeviceName() }

    /** The mic-source row only matters while helper capture is on. */
    private fun refreshMicRow() {
        val on = Settings.shellMic
        micRow?.isEnabled = on
        micRow?.alpha = if (on) 1f else 0.4f
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset all settings?")
            .setMessage("This restores the startup defaults, including pitch and index rate.")
            .setPositiveButton("Reset") { _, _ -> Settings.resetAll(); recreate() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
