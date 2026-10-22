package com.rvcs25.app

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Every user-facing option, saved to disk so the app comes up the same way every time.
 * The defaults below ARE the startup state:
 *   Wi-Fi off · helper mic capture off · force un-mute off · index rate 0 · audio path Direct · helper mic source Auto
 *
 * Call [init] once (RvcApp does); read/write the properties from anywhere.
 */
object Settings {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** Back to the startup defaults (everything, including sliders). */
    fun resetAll() { p.edit().clear().apply() }

    // ---- choices -------------------------------------------------------------------------------
    val PATH_NAMES = listOf("Auto", "Redirection", "Direct")
    val PATH_HELP = listOf(
        "Try Redirection first, fall back to Direct",
        "Only use audio redirection",
        "Only use the direct path (recommended)",
    )
    /** MediaRecorder.AudioSource ids, in the order shown to the user. */
    val MIC_IDS = listOf(0, 1, 5, 6, 7, 9)
    val MIC_NAMES = listOf("Auto", "MIC", "CAMCORDER", "VOICE_RECOGNITION", "VOICE_COMMUNICATION", "UNPROCESSED")

    // ---- startup / live call -------------------------------------------------------------------
    /** Turn Wi-Fi off once when the app starts and the Shizuku helper is connected. */
    var wifiOff by BoolPref("wifiOff", true)
    /** Capture the mic through the Shizuku helper (off = normal app mic). */
    var shellMic by BoolPref("shellMic", false)
    /** Keep the call un-muted even if muted in the dialer. */
    var keepUnmuted by BoolPref("keepUnmuted", false)
    /** 16 kHz uplink (HD-voice calls only), otherwise 8 kHz. */
    var hd16k by BoolPref("hd16k", false)
    /** 0 = Auto, 1 = Redirection, 2 = Direct. */
    var method by IntPref("method", 2, 0, 2)
    /** MediaRecorder.AudioSource id for the helper mic; 0 = Auto. */
    var micSource by IntPref("micSource", 0, 0, 9)

    // ---- voice ---------------------------------------------------------------------------------
    /** Semitones, -12..+12. */
    var pitch by IntPref("pitch", 0, -12, 12)
    /** Index rate in percent, 0..100. 0 skips retrieval entirely (fastest). */
    var indexRate by IntPref("indexRate", 0, 0, 100)
    /** 0 = gate off, 1..40 = threshold of -(60 - value) dB. */
    var gate by IntPref("gate", 0, 0, 40)
    /** Output safety buffer in ms. */
    var slack by IntPref("slack", 40, 0, 100)
    var synthNoise by BoolPref("synthNoise", true)
    var rawMic by BoolPref("rawMic", true)

    val gateDb: Float get() = if (gate == 0) -60f else -(60f - gate)

    // ---- performance (need a model reload) ------------------------------------------------------
    var sustained by BoolPref("sustained", false)
    var cpuBackend by BoolPref("cpuBackend", false)
    var strictNpu by BoolPref("strictNpu", false)

    /** Changes whenever a setting that only applies on model load changes. */
    val loadSignature: String get() = "$sustained/$cpuBackend/$strictNpu"

    // ---- voice library -------------------------------------------------------------
    var voiceId by StrPref("voiceId", "")
    /** GitHub "owner/repo" (or a full release-download link) that holds the base models built by the workflow. */
    var baseUrl by StrPref("baseUrl", "")

    // ---- plumbing ------------------------------------------------------------------------------
    private class BoolPref(val key: String, val def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            p.edit().putBoolean(key, value).apply()
        }
    }

    private class StrPref(val key: String, val def: String) : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { p.edit().putString(key, value).apply() }
    }

    private class IntPref(val key: String, val def: Int, val lo: Int, val hi: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getInt(key, def).coerceIn(lo, hi)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            p.edit().putInt(key, value.coerceIn(lo, hi)).apply()
        }
    }
}
