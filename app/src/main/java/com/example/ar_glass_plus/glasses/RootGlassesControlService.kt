package com.example.ar_glass_plus.glasses

import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.io.FileOutputStream

/**
 * Root-side vendor-report sender for the Air 4 Pro control channel
 * (docs/FIRMWARE_CAPABILITIES.md). Mirrors the verified IMU on/off frame:
 * a 65-byte hidraw write whose payload starts with the 0x66 vendor marker.
 *
 * hid-generic stays attached — opening hidraw neither claims nor detaches
 * USB interface 0, so ar-glass-dpctl's reversible 2D/3D commands and the
 * head-pose service keep working concurrently.
 */
class RootGlassesControlService : RootService() {

    private val stub = object : IRootGlassesControlService.Stub() {
        override fun acquireHidraw(): String = findRayNeoHidraw() ?: ""

        override fun sendCommand(cmd: Int, value: Int, payload: ByteArray?): Int {
            val path = findRayNeoHidraw()
            if (path == null) {
                Log.w(TAG, "sendCommand: Air 4 Pro hidraw not found")
                return -1
            }
            if (cmd !in 0..0xFF || value !in 0..0xFF) return -1
            val frame = ByteArray(FRAME_BYTES)
            // frame[0] is the unnumbered report id; endpoint payload follows.
            frame[VENDOR_MARKER_INDEX] = VENDOR_MARKER.toByte()
            frame[CMD_INDEX] = cmd.toByte()
            frame[VALUE_INDEX] = value.toByte()
            val extra = payload ?: ByteArray(0)
            if (extra.size > frame.size - PAYLOAD_START) return -1
            extra.copyInto(frame, PAYLOAD_START)
            return try {
                FileOutputStream(path).use { stream ->
                    stream.write(frame)
                    stream.flush()
                }
                Log.i(
                    TAG,
                    "sent cmd=0x${cmd.toString(16)} value=$value payload=${extra.size}B -> $path",
                )
                frame.size
            } catch (error: Throwable) {
                Log.w(TAG, "sendCommand failed: ${error.message}")
                -1
            }
        }

        override fun destroy() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent): IBinder = stub

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "root glasses-control service started (uid=${android.os.Process.myUid()})")
    }

    private fun findRayNeoHidraw(): String? {
        val entries = File(SYS_HIDRAW).listFiles() ?: return null
        return entries.firstOrNull { entry ->
            runCatching {
                File(entry, "device/uevent").useLines { lines ->
                    lines.any { it == RAYNEO_HID_ID }
                }
            }.getOrDefault(false)
        }?.let { entry -> "/dev/${entry.name}" }
    }

    private companion object {
        const val TAG = "GlassesCtlRoot"
        const val SYS_HIDRAW = "/sys/class/hidraw"
        const val RAYNEO_HID_ID = "HID_ID=0003:00001BBB:0000AF50"
        const val FRAME_BYTES = 65
        const val VENDOR_MARKER_INDEX = 1
        const val VENDOR_MARKER = 0x66
        const val CMD_INDEX = 2
        const val VALUE_INDEX = 3
        const val PAYLOAD_START = 4
    }
}
