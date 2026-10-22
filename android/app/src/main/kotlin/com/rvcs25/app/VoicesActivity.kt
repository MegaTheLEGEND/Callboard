package com.rvcs25.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Voice library: pick the active voice, add one by link or from a file. Everything happens on the phone. */
class VoicesActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var name: EditText
    private lateinit var url: EditText
    private lateinit var btnGo: Button
    private lateinit var btnPick: Button
    @Volatile private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val accent = 0xFF4FC3F7.toInt()
    private val dim = 0xFF9AA0A6.toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(36), dp(18), dp(28)) }
        fun text(t: String, size: Float = 14f, top: Int = 8) = TextView(this).apply { text = t; textSize = size; setPadding(0, dp(top), 0, 0) }
        root.addView(text("‹ Back", 15f, 0).apply { setTextColor(accent); setOnClickListener { finish() } })
        root.addView(text("Voices", 24f, 6))
        status = text("", 13f, 8).apply { setTextColor(accent) }
        root.addView(status)

        root.addView(text("Your voices", 18f, 18))
        root.addView(text("Tap a voice to select it.", 12f, 2).apply { setTextColor(dim) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(text("Add a voice", 18f, 24))
        root.addView(text("Paste a link to a .pth or a .zip, or pick one from the phone. A .zip may hold the model (.pth) and its " +
            "retrieval index (.index): the voice is named after the file, and the Index rate slider then works for it. " +
            "Pitch-guided (f0) v1/v2 voices only.", 12f, 2).apply { setTextColor(dim) })
        url = EditText(this).apply { hint = "Link to .pth or .zip"; setSingleLine(); textSize = 14f }
        name = EditText(this).apply { hint = "Voice name (optional)"; setSingleLine(); textSize = 14f }
        root.addView(url); root.addView(name)
        btnGo = Button(this).apply { text = "Download & add"; isAllCaps = false; setOnClickListener { fromUrl() } }
        btnPick = Button(this).apply { text = "Choose .pth or .zip from phone"; isAllCaps = false
            setOnClickListener { if (!busy) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1) } }
        root.addView(btnGo); root.addView(btnPick)

        root.addView(text("Base models", 18f, 24))
        val t = Voices.installedTemplates()
        root.addView(text("HuBERT, pitch and synth models every voice needs. Baked into the APK when built by the workflow; " +
            "anything missing is downloaded from your GitHub release.", 12f, 2).apply { setTextColor(dim) })
        root.addView(text("GitHub repo (owner/name) with the “Build base models + APK” release:", 12f, 8))
        root.addView(EditText(this).apply {
            hint = "owner/repo"; setSingleLine(); textSize = 14f; setText(Settings.baseUrl)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { Settings.baseUrl = e.toString().trim() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        root.addView(text(if (t.isEmpty()) "On this phone: nothing yet (fetched when you add a voice)."
            else "On this phone: ${t.joinToString()}", 12f, 4).apply { setTextColor(dim) })
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data
        if (req != 1 || res != RESULT_OK || uri == null) return
        val nm = name.text.toString().trim().ifEmpty { Voices.displayName(uri).substringBeforeLast('.') }
        run(nm) { say -> say("Copying…"); Voices.copyPicked(uri, say) }
    }

    private fun fromUrl() {
        val u = url.text.toString().trim()
        if (u.isEmpty()) { status.text = "Paste a link first."; return }
        val nm = name.text.toString().trim().ifEmpty { u.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifEmpty { "Voice" } }
        run(nm) { say -> Voices.download(u, say) }
    }

    private fun run(nm: String, getFile: ((String) -> Unit) -> Voices.Source) {
        if (busy) return
        busy = true; btnGo.isEnabled = false; btnPick.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val say = { s: String -> ui.post { status.text = s }; Unit }
        Thread {
            try {
                val src = getFile(say)
                val r = Voices.addVoice(src, nm, say)
                Settings.voiceId = r.voice.id
                say("Added “${r.voice.name}” (${r.voice.version}${if (r.voice.hasIndex) ", with index" else ""}). " +
                    "Next: go back and tap “Load model” (first load compiles for the NPU)." +
                    (r.indexNote?.let { "\nThe index couldn’t be used ($it), so the voice was added without it." } ?: ""))
            } catch (e: Throwable) { say("Couldn’t add voice: ${e.message}") }
            finally {
                Voices.cleanTemp()
                ui.post { busy = false; btnGo.isEnabled = true; btnPick.isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); refresh() }
            }
        }.start()
    }

    private fun refresh() {
        list.removeAllViews()
        val voices = Voices.list(); val act = Voices.active()?.id
        if (voices.isEmpty()) list.addView(TextView(this).apply { text = "No voices yet."; setPadding(0, dp(8), 0, 0) })
        for (v in voices) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0)
            addView(TextView(context).apply {
                text = (if (v.id == act) "✓ " else "") + v.label; textSize = 14f
                if (v.id == act) setTextColor(accent)
                setOnClickListener { Settings.voiceId = v.id; refresh() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (v.id != Voices.LEGACY) addView(Button(context).apply {
                text = "Delete"; isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(context).setMessage("Delete “${v.name}”?")
                        .setPositiveButton("Delete") { _, _ -> Voices.delete(v.id); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }
            })
        })
    }
}
