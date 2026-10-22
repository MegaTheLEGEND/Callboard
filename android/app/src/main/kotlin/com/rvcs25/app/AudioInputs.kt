package com.rvcs25.app

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /**
     * One selectable microphone. [info] is the audio device the system exposes; [bt] is a paired Bluetooth device the
     * system does NOT expose as an audio device right now (typical for a smartwatch): it is brought up on demand
     * through the communication-device / hands-free (HFP) link in [activate].
     */
    class Dev(val info: AudioDeviceInfo?, val bt: BluetoothDevice? = null, val btName: String = "") {
        val type: Int get() = info?.type ?: AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val address: String get() = info?.address ?: bt?.address.orEmpty()
        val name: String get() = info?.productName?.toString()?.trim() ?: btName
        val id: Int get() = info?.id ?: 0
        val key: String get() = "$type|$address|$name"
        val viaComm: Boolean get() = type in VIA_COMM
        val label: String get() {
            if (info == null) return name.ifEmpty { "Paired Bluetooth device" } + " · Bluetooth (paired)"
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
    private var hfpProxy: BluetoothProfile? = null
    private var hfpDevice: BluetoothDevice? = null
    private var scoLegacy = false

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

    private fun hasBtPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired audio / wearable devices (smartwatch, headset, car kit), whether or not the system exposes them as audio. */
    @Suppress("MissingPermission")
    private fun bonded(ctx: Context): List<BluetoothDevice> {
        if (!hasBtPermission(ctx)) return emptyList()
        return runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().filter {
                val c = it.bluetoothClass?.majorDeviceClass
                c == null || c == BluetoothClass.Device.Major.AUDIO_VIDEO || c == BluetoothClass.Device.Major.WEARABLE
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("MissingPermission")
    fun list(ctx: Context): List<Dev> {
        val a = am(ctx)
        val out = ArrayList<Dev>()
        a.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in SHOWN }.forEach { out += Dev(it) }
        // Bluetooth devices the system can route calls to but has no live input for (shown as outputs only)
        if (hasBtPermission(ctx)) a.availableCommunicationDevices.filter { it.type in VIA_COMM }.forEach { c ->
            if (out.none { it.viaComm && it.address.equals(c.address, true) }) out += Dev(c)
        }
        // Paired devices not exposed as audio at all
        bonded(ctx).forEach { b ->
            if (out.none { it.address.equals(b.address, true) }) out += Dev(null, b, runCatching { b.name }.getOrNull().orEmpty())
        }
        return out
    }

    /** Display labels; two devices with the same name get their address appended so they can be told apart. */
    fun labels(devs: List<Dev>): List<String> {
        val dup = devs.groupingBy { it.label }.eachCount()
        return devs.map { if ((dup[it.label] ?: 0) > 1 && it.address.isNotBlank()) "${it.label} [${it.address}]" else it.label }
    }

    fun selected(ctx: Context): Dev? =
        Settings.micDeviceKey.takeIf { it.isNotEmpty() }?.let { k ->
            val p = k.split('|')
            val devs = list(ctx)
            devs.firstOrNull { it.key == k }
                // a Bluetooth device is the same device however the system exposes it (input, output or paired only)
                ?: devs.firstOrNull { it.viaComm && (p.getOrNull(0)?.toIntOrNull() ?: -1) in VIA_COMM && p.size > 1 && it.address.equals(p[1], true) }
        }

    fun choose(d: Dev?) {
        Settings.micDeviceKey = d?.key ?: ""
        Settings.micDeviceName = d?.label ?: ""
    }

    /** The saved choice is a Bluetooth-type device and the "Nearby devices" permission is not granted yet. */
    fun btPermissionMissing(ctx: Context): Boolean {
        val t = Settings.micDeviceKey.substringBefore('|').toIntOrNull() ?: return false
        return t in VIA_COMM && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    }

    private fun inputIdFor(ctx: Context, addr: String): Int =
        am(ctx).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type in VIA_COMM && it.address.equals(addr, true)
        }?.id ?: 0

    /** Opens the hands-free (SCO) link to a paired headset / watch directly. True if the request was accepted. */
    @Suppress("MissingPermission")
    private fun startHfp(ctx: Context, d: Dev): Boolean {
        if (!hasBtPermission(ctx)) return false
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val dev = d.bt ?: runCatching { adapter.getRemoteDevice(d.address) }.getOrNull() ?: return false
        val latch = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val ok = runCatching {
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) { proxy = p; latch.countDown() }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        }.getOrDefault(false)
        if (!ok || !latch.await(1500, TimeUnit.MILLISECONDS)) return false
        val h = proxy as? BluetoothHeadset ?: return false
        val started = runCatching { h.startVoiceRecognition(dev) }.getOrDefault(false)
        if (started) { hfpProxy = h; hfpDevice = dev } else runCatching { adapter.closeProfileProxy(BluetoothProfile.HEADSET, h) }
        return started
    }

    /**
     * Routes the saved device for a session. Returns the Oboe device id to open (0 = system default).
     * Blocks up to ~5 s while a Bluetooth link comes up, so call it from a worker thread. A Bluetooth device the system
     * does not expose as an audio device (smartwatch) is brought up on demand: communication device first, then the
     * hands-free link, then the legacy SCO call, until its microphone shows up.
     */
    @Suppress("MissingPermission", "DEPRECATION")
    fun activate(ctx: Context): Int {
        release(ctx)
        note = ""
        val d = selected(ctx)
        if (d == null) {
            if (Settings.micDeviceKey.isNotEmpty()) note = "“${Settings.micDeviceName}” is not connected, using the default microphone."
            return 0
        }
        active = d.label; viaComm = d.viaComm
        if (!d.viaComm) return d.id
        val a = am(ctx)
        if (!hasBtPermission(ctx)) { note = "Allow “Nearby devices” for this app to use a Bluetooth mic."; return 0 }
        fun live() = inputIdFor(ctx, d.address) != 0 ||
            (commSet && a.communicationDevice?.address.equals(d.address, true))
        var how = ""
        val target = a.availableCommunicationDevices.firstOrNull { it.type in VIA_COMM && it.address.equals(d.address, true) }
        if (target != null && try { a.setCommunicationDevice(target) } catch (e: SecurityException) { false }) { commSet = true; how = "call route" }
        if (how.isEmpty() && startHfp(ctx, d)) how = "hands-free link"
        if (how.isEmpty()) { try { a.startBluetoothSco(); a.isBluetoothScoOn = true; scoLegacy = true; how = "SCO link" } catch (e: Exception) {} }
        if (how.isEmpty()) { note = "Couldn’t open ${d.label}; trying the default microphone."; return 0 }
        val end = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < end && !live()) Thread.sleep(100)
        Thread.sleep(150)
        // The link may be up now but the device only appears as an input after it: set it as the route once more
        if (!commSet) a.availableCommunicationDevices.firstOrNull { it.type in VIA_COMM && it.address.equals(d.address, true) }
            ?.let { if (runCatching { a.setCommunicationDevice(it) }.getOrDefault(false)) commSet = true }
        val id = inputIdFor(ctx, d.address)
        if (id == 0) note = "${d.label}: ${how} opened but it offers no microphone yet; using the system route."
        return id
    }

    @Suppress("MissingPermission", "DEPRECATION")
    fun release(ctx: Context) {
        val a = am(ctx)
        if (commSet) { runCatching { a.clearCommunicationDevice() }; commSet = false }
        if (scoLegacy) { runCatching { a.stopBluetoothSco(); a.isBluetoothScoOn = false }; scoLegacy = false }
        hfpProxy?.let { p ->
            hfpDevice?.let { dev -> runCatching { (p as BluetoothHeadset).stopVoiceRecognition(dev) } }
            runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, p) }
        }
        hfpProxy = null; hfpDevice = null
        active = ""; viaComm = false
    }
}
