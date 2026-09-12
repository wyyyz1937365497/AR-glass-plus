package com.example.ar_glass_plus.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Air 4 Pro vendor-report commands (docs/FIRMWARE_CAPABILITIES.md). */
object GlassesCommands {
    const val BRIGHTNESS_SET = 0x09
    const val BRIGHTNESS_SAVE = 0x0D
    const val PANEL_POWER_ON = 0x0E
    const val PANEL_POWER_OFF = 0x0F
    const val PANEL_EYE_SWAP = 0x12
    const val HIGH_DYNAMIC = 0x18
    const val HDR_MODE = 0x1A
    const val COLOR_ENHANCE = 0x1B
    const val RESET_SETTINGS = 0x1D
    const val SAVE_SETTINGS = 0x1F
    const val FRAME_RATE_60 = 0x20
    const val FRAME_RATE_120 = 0x21
    const val AUDIO_PERSISTENCE = 0x25
    const val SBS_TOGGLE = 0x30
    const val AUDIO_ENABLE = 0x34
    const val PSENSOR_DETECT = 0x38
    const val AUDIO_TUBE_MODE = 0x48
    const val AUDIO_MODE = 0x49
    const val VOLUME = 0x50
    const val COLOR_ADJUST = 0x73
    const val WHEEL_KEY_2D3D_SWITCH = 0x58

    /** Official UI and the verified on-device control use 20 brightness levels. */
    const val BRIGHTNESS_MAX_LEVEL = 19

    /** Placeholder until AcquireDeviceInfo readback; official maxVolume unknown. */
    const val VOLUME_MAX_LEVEL = 15

    const val AUDIO_MODE_STANDARD = 0
    const val AUDIO_MODE_WHISPER = 1
    const val AUDIO_MODE_SURROUND = 2
    const val COLOR_MODE_STANDARD = 0
    const val COLOR_MODE_MOVIE = 1
    const val COLOR_MODE_EYE_COMFORT = 2

    internal const val COLOR_OP_PREVIEW = 12
    internal const val COLOR_OP_SAVE = 0xFF
    internal const val COLOR_OP_SAVE_2 = 15

    /**
     * Boolean commands verified on-device: value 1 = feature enabled/normal,
     * value 0 = disabled (wheel key locked / P-sensor detection off).
     */
    const val BOOL_ENABLED = 1
    const val BOOL_DISABLED = 0
}

sealed interface GlassesControlResult {
    data class Sent(val cmd: Int, val value: Int) : GlassesControlResult
    data class Failed(val message: String) : GlassesControlResult
    data object Disconnected : GlassesControlResult
}

/** Minimal send seam shared with UI layers. */
interface GlassesCommandSender {
    suspend fun sendCommand(cmd: Int, value: Int): GlassesControlResult
    /** Applies and persists one of the modes exposed by the Air 4 Pro official UI. */
    suspend fun setColorMode(mode: Int): GlassesControlResult
}

/**
 * App-side handle to the root glasses-control service. Binds lazily, keeps
 * the connection for the owner's lifetime and serializes command sends.
 */
class GlassesControlClient(private val context: Context) : GlassesCommandSender {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private var service: IRootGlassesControlService? = null
    private var connection: ServiceConnection? = null

    private val _state = MutableStateFlow<GlassesControlResult?>(null)
    /** Last command outcome, for UI status lines; null until first send. */
    val state: StateFlow<GlassesControlResult?> = _state

    override suspend fun sendCommand(cmd: Int, value: Int): GlassesControlResult =
        sendCommand(cmd, value, ByteArray(0))

    override suspend fun setColorMode(mode: Int): GlassesControlResult {
        if (mode !in GlassesCommands.COLOR_MODE_STANDARD..GlassesCommands.COLOR_MODE_EYE_COMFORT) {
            return GlassesControlResult.Failed("不支持的图像模式：$mode")
        }
        val preview = sendColorAdjust(
            op = GlassesCommands.COLOR_OP_PREVIEW,
            first = mode,
            second = mode,
        )
        if (preview !is GlassesControlResult.Sent) return preview

        val save = sendColorAdjust(
            op = GlassesCommands.COLOR_OP_SAVE,
            first = 1,
            second = mode,
        )
        if (save !is GlassesControlResult.Sent) return save

        return sendColorAdjust(
            op = GlassesCommands.COLOR_OP_SAVE_2,
            first = 1,
            second = mode,
        )
    }

    suspend fun sendCommand(
        cmd: Int,
        value: Int,
        payload: ByteArray,
    ): GlassesControlResult {
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { ensureConnected() }
        if (bound == null) {
            val result = GlassesControlResult.Disconnected
            _state.value = result
            return result
        }
        val written = withContext(Dispatchers.IO) {
            runCatching { bound.sendCommand(cmd, value, payload) }
                .getOrElse { error ->
                    Log.w(TAG, "sendCommand binder error: ${error.message}")
                    -1
                }
        }
        val result = if (written > 0) {
            GlassesControlResult.Sent(cmd, value)
        } else {
            GlassesControlResult.Failed("眼镜未连接或命令被拒绝 (0x${cmd.toString(16)})")
        }
        _state.value = result
        return result
    }

    private suspend fun sendColorAdjust(
        op: Int,
        first: Int,
        second: Int,
    ): GlassesControlResult = sendCommand(
        cmd = GlassesCommands.COLOR_ADJUST,
        value = op,
        payload = byteArrayOf(0, first.toByte(), second.toByte()),
    )

    fun close() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        scope.cancel()
    }

    private suspend fun ensureConnected(): IRootGlassesControlService? = connectMutex.withLock {
        service?.let { return it }
        val bound = connect()
        service = bound
        return bound
    }

    private suspend fun connect(): IRootGlassesControlService? =
        suspendCancellableCoroutine { continuation ->
            val intent = Intent(context, RootGlassesControlService::class.java)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    continuation.resume(IRootGlassesControlService.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                }
            }
            connection = conn
            RootService.bind(intent, conn)
            continuation.invokeOnCancellation {
                scope.launch { runCatching { context.unbindService(conn) } }
            }
        }

    private companion object {
        const val TAG = "GlassesCtl"
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
