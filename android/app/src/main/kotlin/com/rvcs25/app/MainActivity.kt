package com.rvcs25.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.callboard.ShizukuLink

/**
 * Callboard home screen: a dark button matrix. Tap a pad and the clip plays into the phone call AND on the speaker.
 * Long-press a pad to rename / recolour / reorder / delete it. The voice changer (RVC) lives behind the
 * "Voice changer" button and is secondary.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    private class Pad(val view: View, val glyph: TextView, val dur: TextView, val name: TextView, val color: Int)

    private val pads = HashMap<String, Pad>()
    private var lit: String? = null

    private lateinit var grid: LinearLayout
    private lateinit var helperChip: TextView
    private lateinit var callChip: TextView
    private lateinit var noteView: TextView
    private lateinit var speakerChip: TextView
    private lateinit var levelVal: TextView

    @Volatile private var helperLine = ""
    @Volatile private var importMsg = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ building blocks
    private fun pill(text: String, fg: Int = Cb.TEXT, bg: Int = Cb.SURFACE2, onClick: (() -> Unit)? = null) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(14), dp(8), dp(14), dp(8)); gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(bg) }
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun setChip(tv: TextView, dot: Int, text: String) {
        val s = SpannableString("●  $text")
        s.setSpan(ForegroundColorSpan(dot), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = s
    }

    private fun padBg(color: Int, active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        if (active) setColor(color)
        else { setColor(Cb.blend(Cb.SURFACE, color, 0.14f)); setStroke(dp(1), Cb.alpha(color, 0x99)) }
    }

    private fun fmt(ms: Int): String { val s = (ms + 500) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    // ------------------------------------------------------------------ screen
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(ColorDrawable(Cb.BG))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Cb.BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        // ---- header
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(6)) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = SpannableString("callboard").apply {
                setSpan(ForegroundColorSpan(Cb.ACCENT), 4, 9, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 30f; setTextColor(Cb.TEXT); letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(pill("Voice changer ›", Cb.MUTED) { startActivity(Intent(this, RvcActivity::class.java)) })
        header.addView(titleRow)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        helperChip = pill("", Cb.TEXT, Cb.SURFACE) {
            if (RvcApp.link.state != ShizukuLink.State.CONNECTED) RvcApp.link.requestPermission()
        }
        callChip = pill("", Cb.TEXT, Cb.SURFACE)
        chips.addView(helperChip)
        chips.addView(callChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(8) })
        header.addView(chips)

        noteView = TextView(this).apply {
            textSize = 12f; setTextColor(Cb.WARN); setPadding(dp(2), dp(10), dp(2), 0); visibility = View.GONE
        }
        header.addView(noteView)
        root.addView(header)

        // ---- pad matrix
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(16)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- bottom bar: stop, speaker, level
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
            val r = dp(24).toFloat()
            background = GradientDrawable().apply { setColor(Cb.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stop = TextView(this).apply {
            text = "■  STOP"; textSize = 15f; setTextColor(Cb.DANGER); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.06f
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Cb.alpha(Cb.DANGER, 0x22)); setStroke(dp(1), Cb.alpha(Cb.DANGER, 0xAA))
            }
            setOnClickListener { Player.stop() }
        }
        row.addView(stop, LinearLayout.LayoutParams(0, dp(48), 1f))
        speakerChip = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { Settings.sbSpeaker = !Settings.sbSpeaker; styleSpeaker() }
        }
        row.addView(speakerChip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(10) })
        bar.addView(row)

        val levelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        levelRow.addView(TextView(this).apply { text = "Level in call"; textSize = 12f; setTextColor(Cb.MUTED) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        levelVal = TextView(this).apply { textSize = 12f; setTextColor(Cb.TEXT) }
        levelRow.addView(levelVal)
        bar.addView(levelRow)
        val seek = SeekBar(this).apply {
            max = 275; progress = Settings.sbLevel - 25
            progressTintList = ColorStateList.valueOf(Cb.ACCENT); thumbTintList = ColorStateList.valueOf(Cb.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    Settings.sbLevel = p + 25; levelVal.text = "${p + 25}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        levelVal.text = "${Settings.sbLevel}%"
        bar.addView(seek)
        root.addView(bar)

        setContentView(root)
        styleSpeaker()
        renderGrid()
        ui.post(poll)
        ui.post(helperPoll)
    }

    private fun styleSpeaker() {
        val on = Settings.sbSpeaker
        speakerChip.text = if (on) "Speaker: ON" else "Speaker: OFF"
        speakerChip.setTextColor(if (on) Cb.BG else Cb.MUTED)
        speakerChip.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (on) Cb.ACCENT else Cb.SURFACE2)
        }
    }

    // ------------------------------------------------------------------ pads
    private fun renderGrid() {
        grid.removeAllViews()
        pads.clear()
        lit = null
        val list = Sounds.all()
        if (list.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Add MP3s (or any audio) to build your board.\nTap a pad to play it into the call and on your speaker. " +
                    "Long-press a pad to rename, recolour, move or delete it."
                textSize = 13f; setTextColor(Cb.MUTED); setPadding(dp(8), dp(4), dp(8), dp(10))
            })
        }
        val cols = 3
        val tiles = list.size + 1 // the last tile is "+ Add sound"
        var i = 0
        while (i < tiles) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val idx = i + c
                val lp = LinearLayout.LayoutParams(0, dp(104), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                val v: View = when {
                    idx < list.size -> makePad(list[idx])
                    idx == list.size -> makeAddPad()
                    else -> View(this)
                }
                row.addView(v, lp)
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            i += cols
        }
    }

    private fun makePad(s: Sound): View {
        val col = s.argb
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true; isFocusable = true; background = padBg(col, false)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val glyph = TextView(this).apply { text = "►"; textSize = 14f; setTextColor(col) }
        val dur = TextView(this).apply { text = fmt(s.durationMs); textSize = 11f; setTextColor(Cb.MUTED) }
        top.addView(glyph, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(dur)
        val name = TextView(this).apply {
            text = s.name; textSize = 15f; setTextColor(Cb.TEXT); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        pad.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pad.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pad.setOnClickListener { Player.play(this, s) }
        pad.setOnLongClickListener { editMenu(s); true }
        pad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }
            false
        }
        pads[s.id] = Pad(pad, glyph, dur, name, col)
        return pad
    }

    private fun makeAddPad(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat(); setColor(Cb.SURFACE)
            setStroke(dp(1), Cb.alpha(Cb.MUTED, 0x88), dp(6).toFloat(), dp(5).toFloat())
        }
        addView(TextView(context).apply { text = "+"; textSize = 30f; setTextColor(Cb.MUTED); gravity = Gravity.CENTER })
        addView(TextView(context).apply { text = "Add sound"; textSize = 12f; setTextColor(Cb.MUTED); gravity = Gravity.CENTER })
        setOnClickListener { pick() }
    }

    private fun setLit(id: String?) {
        if (id == lit) return
        lit?.let { pads[it] }?.let { styleLit(it, false) }
        id?.let { pads[it] }?.let { styleLit(it, true) }
        lit = id
    }

    private fun styleLit(p: Pad, on: Boolean) {
        p.view.background = padBg(p.color, on)
        p.glyph.text = if (on) "■" else "►"
        p.glyph.setTextColor(if (on) Cb.BG else p.color)
        p.name.setTextColor(if (on) Cb.BG else Cb.TEXT)
        p.dur.setTextColor(if (on) Cb.alpha(Cb.BG, 0xAA) else Cb.MUTED)
    }

    // ------------------------------------------------------------------ add / edit
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req != REQ_PICK || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        } else {
            data.data?.let { uris += it }
        }
        if (uris.isEmpty()) return
        importMsg = "Adding ${uris.size} sound${if (uris.size == 1) "" else "s"}…"
        val app = applicationContext
        Thread {
            val errors = ArrayList<String>()
            for (u in uris) {
                try { Sounds.import(app, u) } catch (t: Throwable) { errors += t.message ?: "unknown error" }
            }
            importMsg = ""
            ui.post {
                renderGrid()
                if (errors.isNotEmpty())
                    Toast.makeText(this, "Couldn't add: ${errors.distinct().joinToString("; ")}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun editMenu(s: Sound) {
        dialog().setTitle(s.name)
            .setItems(arrayOf("Rename", "Next colour", "Move earlier", "Move later", "Delete")) { _, which ->
                when (which) {
                    0 -> rename(s)
                    1 -> { Sounds.nextColor(s.id); renderGrid() }
                    2 -> { Sounds.move(s.id, -1); renderGrid() }
                    3 -> { Sounds.move(s.id, 1); renderGrid() }
                    4 -> dialog().setTitle("Delete “${s.name}”?")
                        .setPositiveButton("Delete") { _, _ ->
                            if (Player.playingId == s.id) Player.stop()
                            Sounds.delete(s.id); renderGrid()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun rename(s: Sound) {
        val input = EditText(this).apply {
            setText(s.name); setSelectAllOnFocus(true); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine()
        }
        dialog().setTitle("Rename").setView(input)
            .setPositiveButton("Save") { _, _ -> Sounds.rename(s.id, input.text.toString()); renderGrid() }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ live status
    private val poll = object : Runnable {
        override fun run() {
            setLit(Player.playingId)

            when (RvcApp.link.state) {
                ShizukuLink.State.CONNECTED -> setChip(helperChip, Cb.ACCENT, "Helper ready")
                ShizukuLink.State.BINDING -> setChip(helperChip, Cb.WARN, "Connecting…")
                ShizukuLink.State.SHIZUKU_NOT_RUNNING -> setChip(helperChip, Cb.DANGER, "Start Shizuku")
                ShizukuLink.State.PERMISSION_REQUIRED -> setChip(helperChip, Cb.WARN, "Tap to allow Shizuku")
                ShizukuLink.State.PERMISSION_DENIED -> setChip(helperChip, Cb.DANGER, "Shizuku denied · tap to retry")
                ShizukuLink.State.ERROR -> setChip(helperChip, Cb.DANGER, "Helper error · tap to retry")
            }
            if (Player.inCall(this@MainActivity)) setChip(callChip, Cb.ACCENT, "On a call") else setChip(callChip, Cb.MUTED, "No call")

            val parts = ArrayList<String>()
            if (importMsg.isNotEmpty()) parts += importMsg
            if (Player.note.isNotEmpty()) parts += Player.note
            if (helperLine.startsWith("error") && System.currentTimeMillis() - Player.lastPlayAt < 8000)
                parts += "Call audio: $helperLine"
            noteView.text = parts.joinToString("\n")
            noteView.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

            ui.postDelayed(this, 150)
        }
    }

    // The helper status makes a Binder call, so it runs off the main thread. This is also where the
    // "turn Wi-Fi off at startup" step runs once the helper connects (as it did on the old home screen).
    private val helperPoll = object : Runnable {
        override fun run() {
            Thread {
                Startup.runOnce(this@MainActivity)
                helperLine = RvcApp.link.service
                    ?.let { runCatching { it.status().lineSequence().first() }.getOrNull() }.orEmpty()
            }.start()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        ui.removeCallbacks(helperPoll)
        super.onDestroy()
    }

    companion object { private const val REQ_PICK = 7001 }
}
