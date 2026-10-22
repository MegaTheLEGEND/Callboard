package com.rvcs25.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import dev.callboard.rvc.NativeBridge
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Voice library, fully on-device.
 *   files/base/            (adb push once, from export/build_base.py)  HuBERT v1/v2, FCPE, synth templates
 *   filesDir/voices/<id>/  one imported voice: weights.bin + engine.cfg + symlinks into base/
 * A voice is made by reading the .pth natively (NativeBridge.nativeImportPth); the NPU compiles its synth once.
 */
object Voices {
    const val LEGACY = "legacy"

    class Voice(val id: String, val name: String, val version: String, val sr: Int, val hasIndex: Boolean, val dir: File) {
        val label get() = "$name  ·  $version · ${sr / 1000} kHz" + if (hasIndex) " · index" else ""
    }

    /** What was unpacked from a download / picked file: the model and, if the zip had one, its faiss index. */
    class Source(val pth: File, val index: File?)
    /** [indexNote] is set when an index was supplied but could not be used (the voice is still added, without it). */
    class Added(val voice: Voice, val indexNote: String?)

    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }
    val root get() = File(app.filesDir, "voices").apply { mkdirs() }
    val base get() = File(app.getExternalFilesDir(null), "base")

    fun installedTemplates(): List<String> =
        File(base, "templates").listFiles()?.filter { File(it, "synth.onnx").exists() }?.map { it.name }?.sorted() ?: emptyList()

    fun list(): List<Voice> {
        val out = mutableListOf<Voice>()
        val legacy = File(app.getExternalFilesDir(null), "pack")
        if (File(legacy, "engine.cfg").exists()) out += read(LEGACY, legacy, "Legacy pack")
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() }
            ?.forEach { d -> runCatching { out += read(d.name, d, null) } }
        return out
    }

    private fun read(id: String, dir: File, fallbackName: String?): Voice {
        val vj = File(dir, "voice.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        val cj = File(dir, "config.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        return Voice(id, vj?.optString("name") ?: fallbackName ?: id, cj?.optString("version", "?") ?: "?",
            cj?.optInt("tgt_sr", 0) ?: 0, File(dir, "index.bin").exists(), dir)
    }

    fun active(): Voice? = list().let { l -> l.firstOrNull { it.id == Settings.voiceId } ?: l.firstOrNull() }

    fun delete(id: String) {
        if (id == LEGACY) return
        File(root, id).deleteRecursively()   // symlinks are removed, base files stay
        if (Settings.voiceId == id) Settings.voiceId = ""
    }

    /** Converts [src] (a .pth, optionally with its faiss .index) into a new voice. Throws with a readable message.
     *  Slow part is the NPU compile on first Load. */
    fun addVoice(src: Source, name: String, say: (String) -> Unit): Added {
        val pth = src.pth
        say("Reading the voice file…")
        val info = NativeBridge.nativeInspectPth(pth.absolutePath).split("|", limit = 6)
        if (info[0] != "ok") throw Exception(info.getOrElse(1) { "unreadable file" })
        val ver = info[1]; val sr = info[2].toInt()
        if (info[5].isNotEmpty()) throw Exception(info[5])
        val key = "${ver}_$sr"
        val tpl = File(base, "templates/$key")
        ensureBase(ver, key, say)
        val man = baseManifest()
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "voice" } +
            "-" + System.currentTimeMillis().toString(36)
        val dir = File(root, id).apply { mkdirs() }
        var indexNote: String? = null
        try {
            say("Converting weights on the phone…")
            val err = NativeBridge.nativeImportPth(pth.absolutePath, tpl.absolutePath, dir.absolutePath, 0)
            if (err.isNotEmpty()) throw Exception(err)
            // Bake the weights into this voice's own synth.onnx. (Overriding the template's weights at load time
            // with SessionOptions.AddInitializer produced noise on device, so the file itself must hold them.)
            say("Writing the voice model…")
            val bake = NativeBridge.nativeBakeSynth(File(tpl, "synth.onnx").absolutePath,
                File(dir, "weights.bin").absolutePath, File(dir, "synth.onnx").absolutePath)
            if (bake.isNotEmpty()) throw Exception(bake)
            fun link(n: String, target: File) {
                require(target.exists()) { "base file missing: ${target.name}" }
                try { Os.symlink(target.absolutePath, File(dir, n).absolutePath) } catch (e: Exception) { target.copyTo(File(dir, n)) }
            }
            link("hubert.onnx", File(base, "hubert_$ver.onnx"))
            link("fcpe_mel.onnx", File(base, "fcpe_mel.onnx"))
            link("fcpe_net.onnx", File(base, "fcpe_net.onnx"))
            File(base, "fcpe_cents.f32").copyTo(File(dir, "fcpe_cents.f32"))
            File(base, "denoiser_model.onnx").takeIf { it.exists() }?.let { link("denoiser_model.onnx", it) }  // optional neural denoiser
            // The manifest drives the NPU cache tags: HuBERT/FCPE reuse their compiled graphs across voices,
            // the synth is keyed by template + this voice's weights.
            // "baked1" in the key invalidates NPU caches compiled from the old (placeholder-weight) synth.
            val wbin = File(dir, "weights.bin")
            val synthSha = sha("baked1" + shaOf(man, "templates/$key/synth.onnx") + sha256(wbin))
            wbin.delete()   // weights now live inside synth.onnx; the engine must not override them again
            fun ent(sha: String) = JSONObject().put("bytes", 0).put("sha256", sha)
            File(dir, "manifest.json").writeText(JSONObject()
                .put("hubert.onnx", ent(shaOf(man, "hubert_$ver.onnx")))
                .put("fcpe_mel.onnx", ent(shaOf(man, "fcpe_mel.onnx")))
                .put("fcpe_net.onnx", ent(shaOf(man, "fcpe_net.onnx")))
                .put("synth.onnx", ent(synthSha)).toString())
            File(dir, "config.json").writeText(JSONObject().put("version", ver).put("tgt_sr", sr).toString())
            File(dir, "voice.json").writeText(JSONObject().put("name", name).toString())
            if (src.index != null) {
                // A bad index must not lose the voice: the model is added anyway and the reason is reported.
                say("Converting the index…")
                val r = NativeBridge.nativeImportIndex(src.index.absolutePath, File(dir, "index.bin").absolutePath,
                    if (ver == "v2") 768 else 256).split("|", limit = 3)
                if (r[0] == "ok") {
                    val cfg = File(dir, "engine.cfg")
                    cfg.writeText(cfg.readText().replace("has_index 0", "has_index 1"))
                } else {
                    File(dir, "index.bin").delete()
                    indexNote = r.getOrElse(1) { "unreadable index" }
                }
            }
        } catch (e: Throwable) { dir.deleteRecursively(); throw e }
        return Added(read(id, dir, name), indexNote)
    }

    private fun baseManifest() = runCatching { JSONObject(File(base, "manifest.json").readText()) }.getOrDefault(JSONObject())
    private fun saveManifest(m: JSONObject) { base.mkdirs(); File(base, "manifest.json").writeText(m.toString()) }
    private fun entryOf(f: File) = JSONObject().put("bytes", f.length()).put("sha256", sha256(f))
    /** sha256 for a base file; computed from disk (and remembered) if the manifest has no entry for it. */
    private fun shaOf(m: JSONObject, rel: String): String {
        m.optJSONObject(rel)?.optString("sha256")?.takeIf { it.isNotEmpty() }?.let { return it }
        val f = File(base, rel); require(f.exists()) { "base file missing: $rel" }
        val e = entryOf(f); m.put(rel, e); saveManifest(m); return e.getString("sha256")
    }

    private fun releaseUrl(): String {
        val b = Settings.baseUrl.trim().ifEmpty { "MegaTheLEGEND/Callboard" }
        if (b.isEmpty()) throw Exception("Base models are not on this phone yet. Enter your GitHub repo (owner/name) under “Base models” " +
            "after running its “Build base models” workflow — or adb push the base folder (see README).")
        val u = if (Regex("^[\\w.-]+/[\\w.-]+$").matches(b)) "https://github.com/$b/releases/latest/download/" else b
        return if (u.endsWith("/")) u else "$u/"
    }

    /** Gets whatever this voice type needs (HuBERT for its version, FCPE, its synth template): from the models baked
     *  into the APK if present, otherwise from the GitHub release. Already-present files are never touched. */
    private fun ensureBase(ver: String, key: String, say: (String) -> Unit) {
        val hasDn = runCatching { JSONObject(app.assets.open("base/manifest.json").bufferedReader().use { it.readText() }).has("denoiser_model.onnx") }.getOrDefault(false)
        val need = listOfNotNull("denoiser_model.onnx".takeIf { hasDn }) + listOf("hubert_$ver.onnx", "fcpe_mel.onnx", "fcpe_net.onnx", "fcpe_cents.f32", "templates/$key/synth.onnx",
            "templates/$key/template.params", "templates/$key/template.config", "templates/$key/template.cfg")
        base.mkdirs()
        val local = baseManifest()
        if (need.all { File(base, it).exists() && local.has(it) }) return
        val bundled = runCatching { JSONObject(app.assets.open("base/manifest.json").bufferedReader().use { it.readText() }) }.getOrNull()
        var remote: JSONObject? = null; var url = ""
        for (rel in need) {
            val dest = File(base, rel)
            dest.parentFile!!.mkdirs()
            if (dest.exists()) {
                val sz = local.optJSONObject(rel)?.optLong("bytes", 0) ?: 0L
                if (dest.length() == 0L || (sz > 0 && dest.length() != sz)) dest.delete()   // broken leftover: fetch again
                else { if (!local.has(rel)) local.put(rel, bundled?.optJSONObject(rel) ?: entryOf(dest)); saveManifest(local); continue }
            }
            val be = bundled?.optJSONObject(rel)
            if (be != null) {
                say("Unpacking built-in base model $rel…")
                val part = File(dest.path + ".part")
                app.assets.open("base/$rel").use { i -> part.outputStream().use { i.copyTo(it, 1 shl 20) } }
                part.renameTo(dest); local.put(rel, be); saveManifest(local)
            } else {
                if (remote == null) {
                    url = releaseUrl(); say("Fetching the list of base models…")
                    remote = JSONObject(String(fetchBytes(url + "manifest.json")))
                }
                val e = remote.optJSONObject(rel)
                    ?: throw Exception("This build has no $rel. Re-run the “Build base models + APK” workflow with variant $key.")
                fetchFile(url + e.getString("asset"), dest, e.getString("sha256")) { say("Downloading base model $rel… $it") }
                local.put(rel, e); saveManifest(local)
            }
        }
        saveManifest(local)
    }

    private fun open(u: String) = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; setRequestProperty("User-Agent", "RVC-S25")
        if (responseCode >= 400) throw Exception("Download failed (HTTP $responseCode) for ${u.substringAfterLast('/')}")
    }

    private fun fetchBytes(u: String) = open(u).inputStream.use { it.readBytes() }

    private fun fetchFile(u: String, dest: File, sha: String, prog: (String) -> Unit) {
        val c = open(u); val total = c.contentLengthLong
        val part = File(dest.path + ".part"); val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { ins -> part.outputStream().use { out ->
            val b = ByteArray(1 shl 18); var done = 0L; var last = 0L
            while (true) {
                val n = ins.read(b); if (n < 0) break
                out.write(b, 0, n); md.update(b, 0, n); done += n
                if (done - last > (4 shl 20)) { last = done; prog("${done shr 20}" + if (total > 0) " / ${total shr 20} MB" else " MB") }
            }
        } }
        if (md.digest().joinToString("") { "%02x".format(it) } != sha) { part.delete(); throw Exception("${dest.name} was corrupted in transfer, try again") }
        part.renameTo(dest)
    }

    /** Downloads [url] (Hugging Face "blob" links are rewritten to direct links). A .zip is searched for its .pth
     *  and its faiss .index. */
    fun download(url: String, say: (String) -> Unit): Source {
        var u = url.trim().replace("/blob/", "/resolve/")
        require(u.startsWith("http://") || u.startsWith("https://")) { "Enter a full http(s) link" }
        val fname = u.substringBefore('?').substringAfterLast('/').ifEmpty { "voice.pth" }
        val tmp = File(app.cacheDir, "dl_" + System.currentTimeMillis()).apply { mkdirs() }
        val dest = File(tmp, fname)
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", "RVC-S25")
        if (c.responseCode >= 400) throw Exception("Download failed (HTTP ${c.responseCode})")
        val total = c.contentLengthLong
        c.inputStream.use { ins -> dest.outputStream().use { out ->
            val b = ByteArray(1 shl 18); var done = 0L; var last = 0L
            while (true) {
                val n = ins.read(b); if (n < 0) break
                out.write(b, 0, n); done += n
                if (done > (2L shl 30)) throw Exception("File is larger than 2 GB")
                if (done - last > (1 shl 20)) { last = done; say("Downloading… ${done shr 20}" + if (total > 0) " / ${total shr 20} MB" else " MB") }
            }
        } }
        if (!fname.endsWith(".zip", true)) return Source(dest, null)
        return unzipBundle(dest, tmp, say)
    }

    /** First usable .pth (not a "D_*" discriminator) and the best .index ("added_*" preferred, "trained_*" never:
     *  a trained index holds no vectors). Both may sit in sub-folders. */
    private fun unzipBundle(zip: File, tmp: File, say: (String) -> Unit): Source {
        say("Unpacking zip…")
        val pth = File(tmp, "voice.pth"); val idx = File(tmp, "voice.index")
        var havePth = false; var haveIdx = false; var idxAdded = false
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.substringAfterLast('/'); val low = n.lowercase()
                if (low.endsWith(".pth")) {
                    if (havePth || n.startsWith("D_")) continue
                    pth.outputStream().use { z.copyTo(it) }; havePth = true
                } else if (low.endsWith(".index")) {
                    if (low.startsWith("trained")) continue
                    val added = low.startsWith("added")
                    if (haveIdx && (idxAdded || !added)) continue
                    idx.outputStream().use { z.copyTo(it) }; haveIdx = true; idxAdded = added
                }
            }
        }
        zip.delete()
        if (!havePth) throw Exception("No .pth found inside the zip")
        return Source(pth, if (haveIdx) idx else null)
    }

    /** A picked .pth, or a picked .zip (unpacked like a download). */
    fun copyPicked(uri: Uri, say: (String) -> Unit): Source {
        val tmp = File(app.cacheDir, "pick_" + System.currentTimeMillis()).apply { mkdirs() }
        val isZip = displayName(uri).endsWith(".zip", true)
        val f = File(tmp, if (isZip) "bundle.zip" else "voice.pth")
        app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return if (isZip) unzipBundle(f, tmp, say) else Source(f, null)
    }

    fun displayName(uri: Uri): String = app.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: "voice"

    fun cleanTemp() { app.cacheDir.listFiles()?.filter { it.name.startsWith("dl_") || it.name.startsWith("pick_") }?.forEach { it.deleteRecursively() } }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(1 shl 20)
        f.inputStream().use { while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
