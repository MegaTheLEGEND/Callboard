package dev.callboard.shell

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.InputStream

/**
 * Plays into the call uplink, either one clip at a time ([play]) or a live PCM stream ([playStream]).
 * The tap is opened lazily and closed after [IDLE_FRAMES] of silence, so between clips the call behaves
 * normally (your mic is live). While a stream is active the tap stays open and the far end hears ONLY
 * what is injected, not your mic.
 */
internal class Injector(private val ctx: Context) {
    private val lock = Any()
    private val runLock = Any() // serialises tap lifetimes: a new tap never opens while an old one closes
    private var running = false
    private var clip: ShortArray? = null
    private var pos = 0
    private var clipRate = RATE_DEFAULT

    // live stream state (all guarded by lock)
    private var streaming = false
    private var streamRate = RATE_DEFAULT
    private var streamGen = 0
    private var streamIn: InputStream? = null
    private var ring = ShortArray(0)
    private var ringFill = 0
    private var primed = false

    @Volatile private var shuttingDown = false
    @Volatile var status = "idle"; private set
    /** 0 auto, 1 redirection only, 2 direct only; read when a tap opens. */
    @Volatile var method = 0
    /** Keep the uplink open when the call's mute button is pressed (see IShell.setKeepUnmuted). */
    @Volatile var keepUnmuted = true

    @Volatile private var liveTap: Tap? = null

    /**
     * The mute button was pressed while audio is being injected. Returns true if the open tap took the wish (the mute is
     * then applied when the tap closes); false if nothing is being injected, so the caller should set the flag itself.
     */
    fun userMute(on: Boolean): Boolean {
        val t = liveTap ?: return false
        if (!keepUnmuted) return false
        t.setUserMute(on)
        return true
    }

    fun play(pcm: ShortArray, rate: Int) {
        synchronized(lock) {
            if (shuttingDown) return
            closeStreamLocked()
            clip = pcm
            clipRate = rate
            pos = 0
            startThreadLocked()
        }
    }

    fun playStream(input: InputStream, rate: Int) {
        synchronized(lock) {
            if (shuttingDown) { runCatching { input.close() }; return }
            closeStreamLocked()
            clip = null
            streaming = true
            streamRate = rate
            streamIn = input
            ring = ShortArray(rate / 50 * RING_FRAMES)
            ringFill = 0
            primed = false
            val gen = ++streamGen
            Thread({ readLoop(input, gen, rate) }, "stream-read").apply { isDaemon = true; start() }
            startThreadLocked()
        }
    }

    fun endStream() { synchronized(lock) { closeStreamLocked() } }

    fun stop() { synchronized(lock) { clip = null; closeStreamLocked() } }

    fun shutdown() {
        shuttingDown = true
        stop()
    }

    private fun startThreadLocked() {
        if (running) return
        running = true
        status = "opening tap"
        Thread({ run() }, "injector").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun closeStreamLocked() {
        streaming = false
        streamGen++
        streamIn?.let { runCatching { it.close() } }
        streamIn = null
        ringFill = 0
    }

    /** Pulls PCM16LE from the app's pipe into the ring; drops the oldest audio if the app runs ahead. */
    private fun readLoop(input: InputStream, gen: Int, rate: Int) {
        val buf = ByteArray(rate / 50 * 2)
        var carry = -1
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                var i = 0
                val samples = ShortArray((n + 1) / 2 + 1)
                var count = 0
                while (i < n) {
                    if (carry >= 0) {
                        samples[count++] = ((buf[i].toInt() shl 8) or (carry and 0xff)).toShort()
                        carry = -1
                        i++
                    } else if (i + 1 < n) {
                        samples[count++] = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()
                        i += 2
                    } else {
                        carry = buf[i].toInt() and 0xff
                        i++
                    }
                }
                synchronized(lock) {
                    if (gen != streamGen) return
                    if (ringFill + count > ring.size) {
                        val drop = ringFill + count - ring.size
                        if (drop >= ringFill) ringFill = 0
                        else { System.arraycopy(ring, drop, ring, 0, ringFill - drop); ringFill -= drop }
                    }
                    val room = ring.size - ringFill
                    val c = minOf(count, room)
                    System.arraycopy(samples, count - c, ring, ringFill, c)
                    ringFill += c
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "stream read ended: ${t.message}")
        } finally {
            synchronized(lock) { if (gen == streamGen) closeStreamLocked() }
        }
    }

    private fun run() {
      synchronized(runLock) {
        var tap: Tap? = null
        try {
            var rate = synchronized(lock) { if (streaming) streamRate else clipRate }
            while (true) {
                val t = Tap(ctx, rate, method)
                tap = t
                t.start()
                liveTap = t
                status = "live: ${t.method}"
                val frameSize = rate / 50 // 20 ms
                val frame = ShortArray(frameSize)
                val bytes = ByteArray(frameSize * 2)
                var next = SystemClock.elapsedRealtimeNanos()
                var idle = 0
                var n = 0L
                var peak = 0
                var underruns = 0
                var switchTo = 0
                while (true) {
                    var done = false
                    synchronized(lock) {
                        val c = clip
                        if (streaming) {
                            if (streamRate != rate) {
                                switchTo = streamRate
                                done = true
                            } else {
                                // Let one frame accumulate before the first read to absorb pump jitter.
                                if (!primed && ringFill >= frameSize) primed = true
                                if (primed && ringFill >= frameSize) {
                                    System.arraycopy(ring, 0, frame, 0, frameSize)
                                    ringFill -= frameSize
                                    System.arraycopy(ring, frameSize, ring, 0, ringFill)
                                } else {
                                    frame.fill(0)
                                    if (primed) { underruns++; primed = false }
                                }
                                idle = 0
                            }
                        } else if (c != null && clipRate != rate) {
                            switchTo = clipRate // new clip needs a different rate: reopen the tap
                            done = true
                        } else if (c != null) {
                            val len = minOf(frameSize, c.size - pos)
                            System.arraycopy(c, pos, frame, 0, len)
                            frame.fill(0, len, frameSize)
                            pos += len
                            if (pos >= c.size) clip = null
                            idle = 0
                        } else if (++idle > IDLE_FRAMES || shuttingDown) {
                            running = false
                            status = "idle"
                            done = true
                        } else {
                            frame.fill(0)
                        }
                    }
                    if (done) break
                    if (keepUnmuted && n % 5 == 0L && t.micMuted()) t.forceUnmute()
                    if (++n % 10 == 0L && !t.inCall()) {
                        synchronized(lock) { running = false; clip = null; closeStreamLocked(); status = "idle (call ended)" }
                        break
                    }
                    for (i in 0 until frameSize) {
                        val s = frame[i].toInt()
                        if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
                        bytes[2 * i] = s.toByte()
                        bytes[2 * i + 1] = (s shr 8).toByte()
                    }
                    t.write(bytes)
                    if (n % 25 == 0L) {
                        status = "live: ${t.method} rate=$rate frames=$n peakSent=$peak starved=$underruns ${t.diag()}"
                        peak = 0
                    }
                    next += FRAME_NS
                    val wait = next - SystemClock.elapsedRealtimeNanos()
                    if (wait > 0) SystemClock.sleep(wait / 1_000_000)
                    else if (wait < -100_000_000L) next = SystemClock.elapsedRealtimeNanos()
                }
                liveTap = null
                runCatching { t.close() }
                tap = null
                if (switchTo == 0) break
                rate = switchTo
            }
        } catch (t: Throwable) {
            Log.w(TAG, "injector failed", t)
            synchronized(lock) { running = false; clip = null; closeStreamLocked(); status = "error: ${Tap.describe(t)}" }
        } finally {
            liveTap = null
            tap?.let { runCatching { it.close() } }
        }
      }
    }

    companion object {
        private const val TAG = "CallboardInjector"
        const val RATE_DEFAULT = 8000
        private const val FRAME_NS = 20_000_000L
        private const val IDLE_FRAMES = 40           // ~800 ms of silence, then release the call route
        private const val RING_FRAMES = 10           // 200 ms cap: bounds latency if the app runs ahead
    }
}
