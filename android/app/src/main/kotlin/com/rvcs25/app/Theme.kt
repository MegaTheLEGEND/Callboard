package com.rvcs25.app

import android.graphics.Color

/** Callboard's dark palette, shared by the soundboard and the (secondary) voice-changer screens. */
internal object Cb {
    val BG = 0xFF0B0D12.toInt()
    val SURFACE = 0xFF151922.toInt()
    val SURFACE2 = 0xFF1F2433.toInt()
    val TEXT = 0xFFE8EAF0.toInt()
    val MUTED = 0xFF8B93A7.toInt()
    val ACCENT = 0xFF06D6A0.toInt()
    val DANGER = 0xFFFF5A5F.toInt()
    val WARN = 0xFFFFB020.toInt()

    /** Mixes [a] toward [b] by [t] (0..1), ignoring alpha. */
    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
