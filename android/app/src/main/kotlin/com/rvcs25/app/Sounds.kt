package com.rvcs25.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID

class Sound(val id: String, var name: String, var color: Int, val durationMs: Int) {
    val argb: Int get() = Sounds.PALETTE[color.mod(Sounds.PALETTE.size)]
}

/**
 * The soundboard library. Each clip is decoded once when added and stored as raw PCM in files/sounds:
 *   <id>.pcm  mono 48 kHz (played on the phone speaker)
 *   <id>.u16  mono 16 kHz, already low-passed (sent into the call; 8 kHz is derived from it on demand)
 * so a tap never has to decode anything.
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
                if (localFile(id).exists() && uplinkFile(id).exists())
                    items += Sound(id, o.getString("name"), o.getInt("color"), o.getInt("ms"))
            }
        }
    }

    @Synchronized fun all(): List<Sound> = items.toList()
    @Synchronized fun find(id: String): Sound? = items.firstOrNull { it.id == id }

    fun localFile(id: String) = File(dir, "$id.pcm")
    private fun uplinkFile(id: String) = File(dir, "$id.u16")

    /** Decodes [uri] and adds it to the board. Throws [IOException] with a readable message. Call off the main thread. */
    fun import(ctx: Context, uri: Uri): Sound {
        val name = displayName(ctx, uri)
        val pcm = AudioDecode.decodeMono48k(ctx, uri)
        if (pcm.size < LOCAL_RATE / 20) throw IOException("that file has no audio")
        val id = UUID.randomUUID().toString().take(8)
        writeShorts(localFile(id), pcm)
        writeShorts(uplinkFile(id), AudioDecode.downsample(pcm, 3))
        return synchronized(this) {
            Sound(id, name, items.size % PALETTE.size, (pcm.size * 1000L / LOCAL_RATE).toInt()).also { items += it; save() }
        }
    }

    /** The clip as it should go into the call at [rate] (8000 or 16000), scaled by [level]. */
    fun uplink(id: String, rate: Int, level: Float): ShortArray {
        var pcm = readShorts(uplinkFile(id))
        if (rate == 8000) pcm = AudioDecode.downsample(pcm, 2)
        if (level != 1f) for (i in pcm.indices) pcm[i] = (pcm[i] * level).toInt().coerceIn(-32768, 32767).toShort()
        return pcm
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
        uplinkFile(id).delete()
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

    private fun writeShorts(f: File, a: ShortArray) {
        val bb = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(a)
        FileOutputStream(f).use { it.write(bb.array()) }
    }

    private fun readShorts(f: File): ShortArray {
        val bytes = f.readBytes()
        val a = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(a)
        return a
    }
}
