package com.example.ar_glass_plus.headpose.rayneo

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Root transport for the Air 4 Pro HID interrupt stream.
 *
 * hid-generic remains attached: unlike a USB-host claim, opening hidraw does
 * not detach the kernel driver and therefore does not break ar-glass-dpctl's
 * reversible 2D/3D commands. Fusion runs here at the native report rate; the
 * app process only polls the latest complete quaternion at display rate.
 */
class RootHeadPoseService : RootService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val poseLock = Any()
    private val fusion = RayNeoOrientationFusion()
    private val sample = MutableRayNeoImuSample()

    @Volatile
    private var running = false
    @Volatile
    private var input: FileInputStream? = null
    private var trackingJob: Job? = null
    private var hidrawPath: String? = null

    private var stateCode = STATE_STOPPED
    private var latestTimestampNanos = 0L
    private var latestSampleCount = 0L
    private var latestDeviceTick = 0
    private var latestX = 0f
    private var latestY = 0f
    private var latestZ = 0f
    private var latestW = 1f
    private var latestMagneticYawActive = false
    private var latestMagneticFieldStrength = 0f

    private val stub = object : IRootHeadPoseService.Stub() {
        override fun startTracking(): Boolean = this@RootHeadPoseService.startTracking()

        override fun getLatestPose(): DoubleArray = this@RootHeadPoseService.getLatestPose()

        override fun recenter() = this@RootHeadPoseService.recenter()

        override fun stopTracking() = this@RootHeadPoseService.stopTracking()

        override fun destroy() {
            this@RootHeadPoseService.stopTracking()
            stopSelf()
        }
    }

    override fun onBind(intent: android.content.Intent): IBinder = stub

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "root head-pose service started (uid=${android.os.Process.myUid()})")
    }

    private fun startTracking(): Boolean = synchronized(lifecycleLock) {
        if (running && trackingJob?.isActive == true) return true
        val path = findRayNeoHidraw()
        running = false
        if (path == null) {
            publishState(STATE_ERROR)
            Log.e(TAG, "Air 4 Pro hidraw not found")
            return false
        }

        hidrawPath = path
        fusion.reset()
        latestSampleCount = 0L
        running = true
        publishState(STATE_STARTING)
        trackingJob = scope.launch { readReports(path) }
        true
    }

    private fun stopTracking() {
        val path: String?
        synchronized(lifecycleLock) {
            if (!running && trackingJob == null) {
                publishState(STATE_STOPPED)
                return
            }
            running = false
            path = hidrawPath
            try {
                if (path != null && File(path).exists()) writeCommand(path, COMMAND_IMU_OFF)
            } catch (error: Throwable) {
                Log.w(TAG, "IMU-off failed during stop: ${error.message}")
            }
            try {
                input?.close()
            } catch (_: Throwable) {
                // Closing is only used to release a blocking read.
            }
            input = null
            trackingJob?.cancel()
            trackingJob = null
            hidrawPath = null
        }
        publishState(STATE_STOPPED)
        Log.i(TAG, "tracking stopped")
    }

    private fun recenter() {
        synchronized(poseLock) {
            fusion.recenter()
            if (fusion.tracking) copyFusionOutputLocked(SystemClock.elapsedRealtimeNanos())
        }
        Log.i(TAG, "orientation recentered")
    }

    private fun getLatestPose(): DoubleArray = synchronized(poseLock) {
        doubleArrayOf(
            stateCode.toDouble(),
            latestTimestampNanos.toDouble(),
            latestSampleCount.toDouble(),
            latestX.toDouble(),
            latestY.toDouble(),
            latestZ.toDouble(),
            latestW.toDouble(),
            latestDeviceTick.toUInt().toLong().toDouble(),
            if (latestMagneticYawActive) 1.0 else 0.0,
            latestMagneticFieldStrength.toDouble(),
        )
    }

    private suspend fun readReports(path: String) {
        val report = ByteArray(RayNeoImuFrameParser.FRAME_BYTES)
        try {
            val stream = FileInputStream(path)
            input = stream
            writeCommand(path, COMMAND_IMU_ON)
            publishState(STATE_CALIBRATING)
            Log.i(TAG, "IMU-on sent; calibrating from $path")

            while (running && scope.isActive) {
                if (!readReport(stream, report)) break
                if (!RayNeoImuFrameParser.parse(report, sample)) continue
                val now = SystemClock.elapsedRealtimeNanos()
                synchronized(poseLock) {
                    if (fusion.update(sample, now)) {
                        latestSampleCount++
                        latestDeviceTick = sample.deviceTick
                        copyFusionOutputLocked(now)
                        stateCode = STATE_TRACKING
                    }
                }
            }
            if (running) {
                running = false
                publishState(STATE_ERROR)
                Log.e(TAG, "hidraw stream ended unexpectedly")
            }
        } catch (error: Throwable) {
            if (running) {
                running = false
                publishState(STATE_ERROR)
                Log.e(TAG, "hidraw stream failed", error)
            }
        } finally {
            try {
                input?.close()
            } catch (_: Throwable) {
                // Already closed by stopTracking or device removal.
            }
            input = null
        }
    }

    private fun copyFusionOutputLocked(timestampNanos: Long) {
        latestTimestampNanos = timestampNanos
        latestX = fusion.outputX
        latestY = fusion.outputY
        latestZ = fusion.outputZ
        latestW = fusion.outputW
        val magneticYawActive = fusion.magneticYawActive
        if (latestMagneticYawActive != magneticYawActive) {
            Log.i(
                TAG,
                "magnetic yaw ${if (magneticYawActive) "active" else "rejected"} " +
                    "field=${"%.2f".format(fusion.magneticFieldStrength)}",
            )
        }
        latestMagneticYawActive = magneticYawActive
        latestMagneticFieldStrength = fusion.magneticFieldStrength
    }

    private fun publishState(newState: Int) {
        synchronized(poseLock) {
            stateCode = newState
            if (newState == STATE_STOPPED || newState == STATE_ERROR) {
                latestTimestampNanos = 0L
                latestMagneticYawActive = false
                latestMagneticFieldStrength = 0f
            }
        }
    }

    private fun findRayNeoHidraw(): String? {
        val entries = File(SYS_HIDRAW).listFiles() ?: return null
        return entries.firstOrNull { entry ->
            runCatching {
                File(entry, "device/uevent").useLines { lines ->
                    lines.any { it == RAYNEO_HID_ID }
                }
            }.getOrDefault(false)
        }?.let { "/dev/${it.name}" }
    }

    private fun writeCommand(path: String, command: Int) {
        val frame = ByteArray(HIDRAW_WRITE_BYTES)
        // hidraw write byte 0 is the unnumbered report id; endpoint payload follows.
        frame[1] = 0x66
        frame[2] = command.toByte()
        FileOutputStream(path).use { stream ->
            stream.write(frame)
            stream.flush()
        }
    }

    private fun readReport(stream: FileInputStream, report: ByteArray): Boolean {
        var offset = 0
        while (offset < report.size && running) {
            val count = stream.read(report, offset, report.size - offset)
            if (count < 0) return false
            if (count == 0) continue
            offset += count
        }
        return offset == report.size
    }

    override fun onDestroy() {
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RayNeoHeadPoseRoot"
        const val SYS_HIDRAW = "/sys/class/hidraw"
        const val RAYNEO_HID_ID = "HID_ID=0003:00001BBB:0000AF50"
        const val HIDRAW_WRITE_BYTES = 65
        const val COMMAND_IMU_ON = 0x01
        const val COMMAND_IMU_OFF = 0x02

        const val STATE_STOPPED = 0
        const val STATE_STARTING = 1
        const val STATE_CALIBRATING = 2
        const val STATE_TRACKING = 3
        const val STATE_ERROR = 4
    }
}
