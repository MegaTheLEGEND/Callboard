package com.rvcs25.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID

class Sound(val id: String, var name: String, var color: Int, val durationMs: Int) {
    val argb: Int get() = Sounds.PALETTE[color.mod(Sounds.PALETTE.size)]
}

/** Streams a stored clip as mono PCM16 samples, scaled by [level]. Used for the call uplink and the live-voice mixer. */
class PcmReader(file: File, private val level: Float) : Closeable {
    private val ins = BufferedInputStream(FileInputStream(file), 1 shl 16)
    private var bytes = ByteArray(0)

    /** Fills up to [n] samples into [buf]; returns how many it got (0 = end of the clip). */
    fun read(buf: ShortArray, n: Int): Int {
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        var got = 0
        while (got < n * 2) {
            val r = ins.read(bytes, got, n * 2 - got)
            if (r < 0) break
            got += r
        }
        val m = got / 2
        for (i in 0 until m) {
            var v = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort().toInt()
            if (level != 1f) v = (v * level).toInt().coerceIn(-32768, 32767)
            buf[i] = v.toShort()
        }
        return m
    }

    override fun close() { runCatching { ins.close() } }
}

/**
 * The soundboard library. Each clip is decoded once when added and stored as raw PCM in files/sounds, so a tap never
 * has to decode anything and clips of any length (up to [AudioDecode.MAX_SECONDS]) play from disk without being
 * loaded into memory:
 *   <id>.pcm  mono 48 kHz (played on the phone)
 *   <id>.u16  mono 16 kHz, already low-passed (call, HD voice)
 *   <id>.u8   mono  8 kHz, already low-passed (call, normal)
 * That is about 8.6 MB of storage per minute of audio.
 */
object Sounds {
    val PALETTE = intArrayOf(
        0xFFFF5A5F.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFD23F.toInt(), 0xFF06D6A0.toInt(),
        0xFF2EC4B6.toInt(), 0xFF3A86FF.toInt(), 0xFF8338EC.toInt(), 0xFFFF4DA6.toInt(),
    )
    const val LOCAL_RATE = 48000

    private lateinit var dir: File
    private val items = ArrayList<Sound>()

    @Synchronized
    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.applicationContext.filesDir, "sounds").apply { mkdirs() }
        val f = File(dir, "index.json")
        if (!f.exists()) return
        runCatching {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                if (localFile(id).exists() && uplinkFile(id, 16000).exists()) {
                    // Clips stored by an older version have no 8 kHz file: derive it from the 16 kHz one.
                    if (!uplinkFile(id, 8000).exists()) runCatching {
                        val bytes = uplinkFile(id, 16000).readBytes()
                        val s16 = ShortArray(bytes.size / 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s16)
                        val s8 = AudioDecode.downsample(s16, 2)
                        val bb = ByteBuffer.allocate(s8.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        bb.asShortBuffer().put(s8)
                        FileOutputStream(uplinkFile(id, 8000)).use { it.write(bb.array()) }
                    }
                    if (uplinkFile(id, 8000).exists())
                        items += Sound(id, o.getString("name"), o.getInt("color"), o.getInt("ms"))
                }
            }
        }
    }

    @Synchronized fun all(): List<Sound> = items.toList()
    @Synchronized fun find(id: String): Sound? = items.firstOrNull { it.id == id }

    fun localFile(id: String) = File(dir, "$id.pcm")
    private fun uplinkFile(id: String, rate: Int) = File(dir, if (rate == 8000) "$id.u8" else "$id.u16")

    /** Decodes [uri] and adds it to the board. Throws [IOException] with a readable message. Call off the main thread. */
    fun import(ctx: Context, uri: Uri): Sound {
        val name = displayName(ctx, uri)
        val id = UUID.randomUUID().toString().take(8)
        val files = listOf(localFile(id), uplinkFile(id, 16000), uplinkFile(id, 8000))
        val samples = try {
            AudioDecode.decodeToFiles(ctx, uri, files[0], files[1], files[2])
        } catch (t: Throwable) {
            files.forEach { it.delete() }
            throw if (t is IOException) t else IOException(t.message ?: "couldn't decode that file")
        }
        if (samples < LOCAL_RATE / 20) {
            files.forEach { it.delete() }
            throw IOException("that file has no audio")
        }
        return synchronized(this) {
            Sound(id, name, items.size % PALETTE.size, (samples * 1000L / LOCAL_RATE).toInt()).also { items += it; save() }
        }
    }

    /** Streams the clip for the call at [rate] (8000 or 16000), scaled by [level]. The caller closes it. */
    fun openUplink(id: String, rate: Int, level: Float) = PcmReader(uplinkFile(id, rate), level)

    /** The whole clip in memory, for short clips only. */
    fun uplink(id: String, rate: Int, level: Float): ShortArray {
        val f = uplinkFile(id, rate)
        val n = (f.length() / 2).toInt()
        val a = ShortArray(n)
        val r = PcmReader(f, level)
        val got = try { r.read(a, n) } finally { r.close() }
        return if (got == n) a else a.copyOf(got)
    }

    @Synchronized fun rename(id: String, name: String) {
        find(id)?.name = name.trim().ifEmpty { "Sound" }
        save()
    }

    @Synchronized fun nextColor(id: String) {
        find(id)?.let { it.color = (it.color + 1) % PALETTE.size }
        save()
    }

    @Synchronized fun move(id: String, delta: Int) {
        val i = items.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= items.size) return
        Collections.swap(items, i, j)
        save()
    }

    @Synchronized fun delete(id: String) {
        items.removeAll { it.id == id }
        localFile(id).delete()
        uplinkFile(id, 16000).delete()
        uplinkFile(id, 8000).delete()
        save()
    }

    private fun save() {
        val a = JSONArray()
        for (s in items) a.put(JSONObject().put("id", s.id).put("name", s.name).put("color", s.color).put("ms", s.durationMs))
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(File(dir, "index.json"))
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        val n = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "Sound"
        return n.substringBeforeLast('.').trim().ifEmpty { "Sound" }
    }
}
