package com.rvcs25.app

import android.content.Context
import android.net.wifi.WifiManager

/**
 * One-time work done per app process as soon as the Shizuku helper is connected.
 * Currently: switch Wi-Fi off (if the "Turn Wi-Fi off at startup" setting is on).
 * It only runs once, so if you switch Wi-Fi back on later the app will not fight you.
 */
object Startup {
    @Volatile private var done = false

    /** One line for the status panel; empty until the startup step has run. */
    @Volatile var note = ""; private set

    fun wifiOn(ctx: Context): Boolean = runCatching {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(false)

    /** Call off the main thread (it makes a Binder call). Safe to call repeatedly. */
    fun runOnce(ctx: Context) {
        if (done) return
        val svc = RvcApp.link.service ?: return      // helper not up yet; try again on the next poll
        done = true
        note = when {
            !Settings.wifiOff -> ""
            !wifiOn(ctx) -> "Wi-Fi: off"
            runCatching { svc.setWifiEnabled(false) }.getOrDefault(false) -> "Wi-Fi: turned off at startup"
            else -> "Wi-Fi: could not be turned off, switch it off manually"
        }
    }
}
