package com.rvcs25.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the in-call shortcut back after a reboot or an app update (only if the setting is on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) CallShortcutService.sync(ctx)
    }
}
