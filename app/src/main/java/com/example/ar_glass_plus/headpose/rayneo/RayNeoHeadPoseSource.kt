package com.example.ar_glass_plus.headpose.rayneo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.ar_glass_plus.headpose.api.HeadPoseSource
import com.example.ar_glass_plus.headpose.api.HeadPoseState
import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.Vec3
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume

/** Air 4 Pro HeadPoseSource backed by the root hidraw reader/fusion service. */
class RayNeoHeadPoseSource(
    private val context: Context,
) : HeadPoseSource {
    private data class TimedCamera(
        val timestampNanos: Long,
        val camera: SpatialCamera,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latest = AtomicReference<TimedCamera?>(null)
    private val _state = MutableStateFlow<HeadPoseState>(HeadPoseState.Inactive)
    override val state: StateFlow<HeadPoseState> = _state.asStateFlow()

    @Volatile
    private var active = false
    @Volatile
    private var service: IRootHeadPoseService? = null
    private var pollJob: Job? = null
    private var serviceConnection: ServiceConnection? = null

    override suspend fun start(): Boolean {
        if (active && pollJob?.isActive == true) return true
        setState(HeadPoseState.Starting)
        val connectedService = service ?: connect()
        if (connectedService == null) {
            setState(HeadPoseState.Error("root 头姿服务连接失败"))
            return false
        }
        val started = runCatching { connectedService.startTracking() }.getOrElse { error ->
            Log.e(TAG, "startTracking failed", error)
            false
        }
        if (!started) {
            setState(HeadPoseState.Error("未找到 Air 4 Pro hidraw"))
            return false
        }

        active = true
        pollJob?.cancel()
        pollJob = scope.launch { pollLatest(connectedService) }
        return true
    }

    override fun stop() {
        active = false
        pollJob?.cancel()
        pollJob = null
        latest.set(null)
        setState(HeadPoseState.Inactive)
        scope.launch {
            runCatching { service?.stopTracking() }
                .onFailure { Log.w(TAG, "stopTracking failed: ${it.message}") }
        }
    }

    override fun recenter() {
        if (!active) return
        scope.launch {
            runCatching { service?.recenter() }
                .onFailure { Log.w(TAG, "recenter failed: ${it.message}") }
        }
    }

    override fun latestCamera(nowNanos: Long): SpatialCamera? {
        val value = latest.get() ?: return null
        val age = nowNanos - value.timestampNanos
        return value.camera.takeIf { age in 0..STALE_AFTER_NANOS }
    }

    private suspend fun connect(): IRootHeadPoseService? {
        val binder = suspendCancellableCoroutine<IBinder?> { continuation ->
            val intent = Intent(context, RootHeadPoseService::class.java)
            val connection = object : ServiceConnection {
                private var initialResultDelivered = false

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = binder?.let(IRootHeadPoseService.Stub::asInterface)
                    if (!initialResultDelivered) {
                        initialResultDelivered = true
                        if (continuation.isActive) continuation.resume(binder)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    if (!initialResultDelivered) {
                        initialResultDelivered = true
                        if (continuation.isActive) continuation.resume(null)
                    } else if (active) {
                        latest.set(null)
                        setState(HeadPoseState.Error("root 头姿服务已断开"))
                    }
                }
            }
            serviceConnection = connection
            RootService.bind(intent, connection)
        }
        return binder?.let(IRootHeadPoseService.Stub::asInterface).also { service = it }
    }

    private suspend fun pollLatest(connectedService: IRootHeadPoseService) {
        try {
            while (active && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val values = connectedService.latestPose
                val rootState = values.getOrNull(INDEX_STATE)?.toInt() ?: ROOT_STATE_ERROR
                when (rootState) {
                    ROOT_STATE_STARTING -> setState(HeadPoseState.Starting)
                    ROOT_STATE_CALIBRATING -> setState(HeadPoseState.Calibrating)
                    ROOT_STATE_TRACKING -> consumeTrackingPose(values)
                    ROOT_STATE_ERROR -> {
                        latest.set(null)
                        setState(HeadPoseState.Error("Air 4 Pro IMU 数据流失败"))
                    }
                    else -> if (active) setState(HeadPoseState.Starting)
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (error: Throwable) {
            if (active) {
                latest.set(null)
                setState(HeadPoseState.Error("头姿读取失败：${error.message ?: error.javaClass.simpleName}"))
                Log.e(TAG, "pose polling failed", error)
            }
        }
    }

    private fun consumeTrackingPose(values: DoubleArray) {
        if (values.size < VALUE_COUNT) {
            setState(HeadPoseState.Error("头姿 IPC 数据不完整"))
            return
        }
        val timestampNanos = values[INDEX_TIMESTAMP].toLong()
        val rawQ = Quat(
            values[INDEX_QX].toFloat(),
            values[INDEX_QY].toFloat(),
            values[INDEX_QZ].toFloat(),
            values[INDEX_QW].toFloat(),
        )
        val normSquared =
            rawQ.x * rawQ.x + rawQ.y * rawQ.y + rawQ.z * rawQ.z + rawQ.w * rawQ.w
        if (
            !normSquared.isFinite() ||
            normSquared !in MIN_QUAT_NORM_SQUARED..MAX_QUAT_NORM_SQUARED
        ) {
            setState(HeadPoseState.Error("头姿四元数无效"))
            return
        }
        val q = rawQ.normalize()
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (timestampNanos <= 0L || now - timestampNanos !in 0..STALE_AFTER_NANOS) {
            setState(HeadPoseState.Stalled)
            return
        }
        latest.set(TimedCamera(timestampNanos, SpatialCamera(position = Vec3.ZERO, orientation = q)))
        val magneticYawActive =
            values.getOrNull(INDEX_MAGNETIC_YAW_ACTIVE)?.let { it >= 0.5 } ?: false
        setState(if (magneticYawActive) TRACKING_MAGNETIC else TRACKING_INERTIAL)
    }

    private fun setState(value: HeadPoseState) {
        if (_state.value != value) {
            _state.value = value
            Log.i(TAG, "state -> $value")
        }
    }

    private companion object {
        const val TAG = "RayNeoHeadPose"
        const val POLL_INTERVAL_MS = 8L
        const val STALE_AFTER_NANOS = 100_000_000L
        const val MIN_QUAT_NORM_SQUARED = 0.5f
        const val MAX_QUAT_NORM_SQUARED = 1.5f

        const val ROOT_STATE_STARTING = 1
        const val ROOT_STATE_CALIBRATING = 2
        const val ROOT_STATE_TRACKING = 3
        const val ROOT_STATE_ERROR = 4

        const val INDEX_STATE = 0
        const val INDEX_TIMESTAMP = 1
        const val INDEX_QX = 3
        const val INDEX_QY = 4
        const val INDEX_QZ = 5
        const val INDEX_QW = 6
        const val INDEX_MAGNETIC_YAW_ACTIVE = 8

        val TRACKING_MAGNETIC = HeadPoseState.Tracking(magneticYawActive = true)
        val TRACKING_INERTIAL = HeadPoseState.Tracking(magneticYawActive = false)
        const val VALUE_COUNT = 8
    }
}
