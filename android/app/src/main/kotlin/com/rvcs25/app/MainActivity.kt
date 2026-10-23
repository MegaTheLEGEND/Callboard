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
import android.os.SystemClock
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.callboard.ShizukuLink

/**
 * CallBoard home screen: a dark button matrix. Tap a pad and the clip plays into the phone call AND on this phone.
 * The bottom bar has Stop (the clip), Mute (follows the dialer) and End (hang up).
 * Long-press a pad to rename / recolour / reorder / delete it. The voice changers live behind the
 * "Voice changer" button (it asks: classic modulation or RVC) and are secondary.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    private class Pad(val view: View, val glyph: TextView, val dur: TextView, val name: TextView, val color: Int)

    private val pads = HashMap<String, Pad>()
    private var lit: String? = null

    private lateinit var grid: LinearLayout
    private lateinit var helperChip: TextView
    private lateinit var callChip: TextView
    private lateinit var loopChip: TextView
    private lateinit var noteView: TextView
    private lateinit var rvcPill: TextView
    private lateinit var stopBtn: TextView
    private lateinit var muteBtn: TextView
    private lateinit var endBtn: TextView
    private lateinit var levelVal: TextView

    @Volatile private var helperLine = ""
    @Volatile private var importMsg = ""
    @Volatile private var flashMsg = ""
    @Volatile private var flashAt = 0L
    private var shownMuted = false
    private var muteTouchedAt = 0L
    private var rvcShownOn: Boolean? = null
    private var tick = 0

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
        titleRow.addView(ImageView(this).apply { setImageResource(R.drawable.ic_callboard_mark) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })
        titleRow.addView(TextView(this).apply {
            text = SpannableString("CallBoard").apply {
                setSpan(ForegroundColorSpan(Cb.ACCENT), 4, 9, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 30f; setTextColor(Cb.TEXT); letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rvcPill = pill("●  Voice changer ›", Cb.MUTED) { openVoiceChanger() }
        titleRow.addView(rvcPill)
        header.addView(titleRow)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        helperChip = pill("", Cb.TEXT, Cb.SURFACE) {
            if (RvcApp.link.state != ShizukuLink.State.CONNECTED) RvcApp.link.requestPermission()
        }
        callChip = pill("", Cb.TEXT, Cb.SURFACE)
        chips.addView(helperChip)
        chips.addView(callChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(8) })
        loopChip = pill("") { Player.loop = !Player.loop; styleLoop() }
        chips.addView(loopChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(8) })
        styleLoop()
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

        // ---- bottom bar: stop clip, mute, end call, then the level slider
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
            val r = dp(24).toFloat()
            background = GradientDrawable().apply { setColor(Cb.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stopBtn = barButton("■ STOP") { Player.stop() }
        muteBtn = barButton("Mute") { toggleMute() }
        endBtn = barButton("End") { hangUp() }
        for ((i, v) in listOf(stopBtn, muteBtn, endBtn).withIndex()) {
            row.addView(v, LinearLayout.LayoutParams(0, dp(48), if (i == 0) 1.15f else 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
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
        renderGrid()
        ui.post(poll)
        ui.post(helperPoll)
    }

    // ------------------------------------------------------------------ voice changer chooser
    /** "Voice changer" asks which kind: classic (modulation presets) or RVC (AI voice). The two never run together. */
    private fun openVoiceChanger() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(2)) }
        val holder = arrayOfNulls<AlertDialog>(1)

        fun card(title: String, sub: String, running: Boolean, go: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Cb.SURFACE2)
                if (running) setStroke(dp(1), Cb.ACCENT)
            }
            addView(TextView(context).apply {
                text = if (running) "$title   ● running" else title
                textSize = 16f; setTextColor(Cb.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(Cb.MUTED); setPadding(0, dp(4), 0, 0) })
            setOnClickListener { holder[0]?.dismiss(); go() }
        }

        box.addView(card("Classic voice changer", "Modulation presets (deep, chipmunk, robot, alien, demon, telephone) or your own custom mix. Fast, no model.",
            VoiceService.classicRunning) { chooseVoiceChanger(true) })
        box.addView(card("RVC voice changer", "AI voice conversion using your loaded voice model.",
            VoiceService.rvcRunning) { chooseVoiceChanger(false) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        holder[0] = dialog().setTitle("Voice changer").setView(box).setNegativeButton("Cancel", null).show()
    }

    private fun chooseVoiceChanger(classic: Boolean) {
        val target = if (classic) ClassicActivity::class.java else RvcActivity::class.java
        val otherRunning = if (classic) VoiceService.rvcRunning else VoiceService.classicRunning
        if (!otherRunning) { startActivity(Intent(this, target)); return }
        dialog().setTitle("Switch voice changer?")
            .setMessage(
                if (classic) "RVC is running. RVC and the classic voice changer can't run at the same time, so RVC will be stopped."
                else "The classic voice changer is running. RVC and the classic voice changer can't run at the same time, so the classic one will be stopped."
            )
            .setPositiveButton("Stop and switch") { _, _ -> VoiceService.stop(this); startActivity(Intent(this, target)) }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ bar buttons
    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setOnClickListener { onClick() }
    }

    /** Outlined when [fill] is null, otherwise solid. */
    private fun styleBtn(v: TextView, color: Int, fill: Boolean, dim: Boolean = false) {
        v.setTextColor(if (fill) Cb.BG else color)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            if (fill) setColor(color) else { setColor(Cb.alpha(color, 0x18)); setStroke(dp(1), Cb.alpha(color, 0xAA)) }
        }
        v.alpha = if (dim) 0.45f else 1f
    }

    /** Loop pill: red while off, green while looping. */
    private fun styleLoop() {
        val on = Player.loop
        val c = if (on) Cb.ACCENT else Cb.DANGER
        loopChip.text = "↻  Loop"
        loopChip.setTextColor(c)
        loopChip.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat(); setColor(Cb.alpha(c, 0x26)); setStroke(dp(1), c)
        }
    }

    private fun flash(msg: String) { flashMsg = msg; flashAt = System.currentTimeMillis() }

    private fun toggleMute() {
        if (!Player.inCall(this)) { flash("No call to mute."); return }
        val svc = RvcApp.link.service ?: run { flash("Connect the Shizuku helper first (tap its chip)."); return }
        // Go by what the button shows: while a clip plays the helper holds the system flag clear, so it can't be read then.
        val want = !shownMuted
        shownMuted = want
        muteTouchedAt = SystemClock.elapsedRealtime()
        Thread {
            val ok = runCatching { svc.setMicMuted(want) }.getOrDefault(false)
            if (!ok) flash("Couldn't change mute.")
        }.start()
    }

    private fun hangUp() {
        if (!Player.inCall(this)) { flash("No call to end."); return }
        Player.stop()
        val svc = RvcApp.link.service
        Thread {
            val ok = svc != null && runCatching { svc.endCall() }.getOrDefault(false)
            if (!ok) ui.post { hangUpFallback() }
        }.start()
    }

    /** Without the helper: Android's own hang-up, which needs the phone-calls permission. */
    private fun hangUpFallback() {
        if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ANSWER_PHONE_CALLS), 2)
            flash("Allow the phone-calls permission, then tap End again.")
            return
        }
        @Suppress("DEPRECATION")
        val ok = runCatching { getSystemService(android.telecom.TelecomManager::class.java).endCall() }.getOrDefault(false)
        if (!ok) flash("Couldn't end the call.")
    }

    // ------------------------------------------------------------------ pads
    private fun renderGrid() {
        grid.removeAllViews()
        pads.clear()
        lit = null
        val list = Sounds.all()
        if (list.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Add MP3s (or any audio) to build your board.\nTap a pad to play it into the call and on this phone. " +
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
            // Dot on the Voice changer button: green while the voice changer is running, grey otherwise.
            val rvcOn = VoiceService.running
            if (rvcOn != rvcShownOn) {
                rvcShownOn = rvcOn
                val sp = SpannableString("●  Voice changer ›")
                sp.setSpan(ForegroundColorSpan(if (rvcOn) Cb.ACCENT else Cb.MUTED), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                rvcPill.text = sp
            }

            val inCall = Player.inCall(this@MainActivity)
            if (inCall) setChip(callChip, Cb.ACCENT, "On a call") else setChip(callChip, Cb.MUTED, "No call")

            // Mute / speaker follow the dialer (read from the system), a few times a second.
            if (tick++ % 2 == 0) {
                // Only if "keep un-muted" is on does the helper lift the system mute flag while a clip plays (and put it
                // back after); then the flag can't be read, so keep showing what you chose.
                val busy = (Settings.keepUnmuted &&
                    (Player.playingId != null || System.currentTimeMillis() - Player.lastEndAt < 1500)) ||
                    SystemClock.elapsedRealtime() - muteTouchedAt < 700
                if (!busy) shownMuted = inCall && Player.micMuted(this@MainActivity)
                muteBtn.text = if (shownMuted) "Muted" else "Mute"
                styleBtn(muteBtn, Cb.WARN, shownMuted, !inCall)
                styleBtn(stopBtn, Cb.TEXT, false)
                styleBtn(endBtn, Cb.DANGER, true, !inCall)
            }

            val parts = ArrayList<String>()
            if (importMsg.isNotEmpty()) parts += importMsg
            if (Player.note.isNotEmpty()) parts += Player.note
            if (flashMsg.isNotEmpty() && System.currentTimeMillis() - flashAt < 5000) parts += flashMsg
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
