package com.rvcs25.app

/**
 * Voice-changer engine state that has to outlive the screen. The engine runs in the app process, not in the Activity,
 * so "model loaded" / "loading" must not be forgotten when you leave the Voice changer screen and come back.
 */
object RvcSession {
    @Volatile var loaded = false
    @Volatile var loading = false
    @Volatile var loadedSig = ""
    @Volatile var loadedVoice = ""
}
