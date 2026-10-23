package com.rvcs25.app

import android.app.Application
import dev.callboard.ShizukuLink

class RvcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Voices.init(this)
        Sounds.init(this)
        link = ShizukuLink(this).also { it.start() }
    }

    companion object {
        /** Keeps the Shizuku shell-uid helper bound; the live-call path talks to the call through it. */
        lateinit var link: ShizukuLink
            private set
    }
}
