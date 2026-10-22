package dev.callboard.shell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream

/**
 * Microphone capture that runs inside the Shizuku shell process, impersonating com.android.shell like
 * [Tap]. Android can silence a normal app's microphone during a cellular call; a shell-uid capture with
 * the same attribution trick as the call tap may still get audio, so LiveVoice tries this first.
 * Frames (20 ms, mono PCM16LE) are written into the pipe the app handed us.
 */
internal class MicCapture(private val ctx: Context) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var sink: FileOutputStream? = null

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(out: ParcelFileDescriptor, rate: Int, source: Int) {
        stop()
        val frame = rate / 50
        val minRec = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minRec > 0) { "record buffer size $minRec" }
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val rec = AudioRecord.Builder().setAudioSource(source).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minRec, frame * 2 * 8))
            .also { AudioBuilderContext.apply(it, ctx, Context::class.java) { ShellEnv.prepareAudioAttribution(ctx) } }
            .build()
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "mic record not initialized (source $source)" }
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "mic record did not start" }
        } catch (t: Throwable) {
            runCatching { rec.release() }
            runCatching { out.close() }
            throw t
        }
        record = rec
        val os = ParcelFileDescriptor.AutoCloseOutputStream(out)
        sink = os
        running = true
        thread = Thread({
            val shorts = ShortArray(frame)
            val bytes = ByteArray(frame * 2)
            try {
                while (running) {
                    val n = rec.read(shorts, 0, frame, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        bytes[2 * i] = shorts[i].toByte()
                        bytes[2 * i + 1] = (shorts[i].toInt() shr 8).toByte()
                    }
                    os.write(bytes, 0, n * 2) // blocks if the app is not reading; EPIPE when it closed its end
                }
            } catch (t: Throwable) {
                Log.i(TAG, "mic pump ended: ${t.message}")
            } finally {
                running = false
                runCatching { rec.stop() }
                runCatching { rec.release() }
                runCatching { os.close() }
            }
        }, "mic-capture").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
        Log.i(TAG, "mic capture started rate=$rate source=$source")
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { sink?.close() }       // unblocks a pending write
        thread?.let { runCatching { it.join(300) } }
        thread = null
        sink = null
        record = null
    }

    private companion object { const val TAG = "CallboardMic" }
}
