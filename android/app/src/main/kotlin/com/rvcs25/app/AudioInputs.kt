package com.rvcs25.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock

/**
 * Microphone devices the system offers right now (phone mic, Bluetooth headphones / hands-free kit / watch, wired,
 * USB...), and the routing needed to actually capture from the one the user picked in Settings.
 *
 * The choice is remembered by type + address + name (an AudioDeviceInfo id changes every time a device reconnects).
 * Bluetooth mics only carry audio while that headset is the platform's "communication device" (the SCO / LE call
 * link), so [activate] switches to it for the session and [release] hands it back.
 */
object AudioInputs {
    private val SHOWN = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_DOCK,
    )
    /** Types that need the communication-device switch before they carry audio. */
    private val VIA_COMM = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)

    class Dev(val info: AudioDeviceInfo) {
        val id: Int get() = info.id
        val key: String get() = "${info.type}|${info.address}|${info.productName}"
        val viaComm: Boolean get() = info.type in VIA_COMM
        val label: String get() {
            val name = info.productName?.toString()?.trim().orEmpty()
            return if (info.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                "Phone microphone" + info.address.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            else name.ifEmpty { "Unnamed device" } + " · " + typeName(info.type)
        }
    }

    /** What the running session captures from (for the UI), and anything the user should know about it. */
    @Volatile var active = ""; private set
    @Volatile var note = ""; private set
    /** True while the chosen device is a Bluetooth / hearing-aid type (then "unprocessed" capture does not apply). */
    @Volatile var viaComm = false; private set
    private var commSet = false

    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth hands-free"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        AudioDeviceInfo.TYPE_DOCK -> "dock"
        else -> "type $t"
    }

    fun list(ctx: Context): List<Dev> =
        am(ctx).getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in SHOWN }.map { Dev(it) }

    /** Display labels; two devices with the same name get their address appended so they can be told apart. */
    fun labels(devs: List<Dev>): List<String> {
        val dup = devs.groupingBy { it.label }.eachCount()
        return devs.map { if ((dup[it.label] ?: 0) > 1 && it.info.address.isNotBlank()) "${it.label} [${it.info.address}]" else it.label }
    }

    fun selected(ctx: Context): Dev? =
        Settings.micDeviceKey.takeIf { it.isNotEmpty() }?.let { k -> list(ctx).firstOrNull { it.key == k } }

    fun choose(d: Dev?) {
        Settings.micDeviceKey = d?.key ?: ""
        Settings.micDeviceName = d?.label ?: ""
    }

    /** The saved choice is a Bluetooth-type device and the "Nearby devices" permission is not granted yet. */
    fun btPermissionMissing(ctx: Context): Boolean {
        val t = Settings.micDeviceKey.substringBefore('|').toIntOrNull() ?: return false
        return t in VIA_COMM && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Routes the saved device for a session. Returns the Oboe device id to open (0 = system default).
     * Blocks up to ~2.5 s while a Bluetooth link comes up, so call it from a worker thread.
     */
    fun activate(ctx: Context): Int {
        release(ctx)
        note = ""
        val d = selected(ctx)
        if (d == null) {
            if (Settings.micDeviceKey.isNotEmpty()) note = "“${Settings.micDeviceName}” is not connected, using the default microphone."
            return 0
        }
        active = d.label; viaComm = d.viaComm
        if (d.viaComm) {
            val a = am(ctx)
            val target = a.availableCommunicationDevices.firstOrNull { it.type == d.info.type && it.address == d.info.address }
            val ok = target != null && try { a.setCommunicationDevice(target) } catch (e: SecurityException) { false }
            if (ok) {
                commSet = true
                val end = SystemClock.elapsedRealtime() + 2500
                while (SystemClock.elapsedRealtime() < end && a.communicationDevice?.id != target!!.id) Thread.sleep(100)
                Thread.sleep(150)
            } else {
                note = if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    "Allow “Nearby devices” for this app to use a Bluetooth mic." else "Couldn’t switch to ${d.label}; trying it directly."
            }
        }
        return d.id
    }

    fun release(ctx: Context) {
        if (commSet) { runCatching { am(ctx).clearCommunicationDevice() }; commSet = false }
        active = ""; viaComm = false
    }
}
