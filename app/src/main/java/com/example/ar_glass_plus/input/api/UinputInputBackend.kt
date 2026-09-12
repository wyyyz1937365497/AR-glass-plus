package com.example.ar_glass_plus.input.api

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import com.example.ar_glass_plus.input.uinput.IRootMouseService
import com.example.ar_glass_plus.input.uinput.RootMouseService
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Real virtual-mouse backend via a libsu RootService that owns a /dev/uinput
 * REL mouse pinned to the content display. IPC is rate-limited to ~60 Hz with
 * delta accumulation (adapted from AR-Touchpad, Apache-2.0).
 */
class UinputInputBackend(
    private val context: Context,
) : InputBackend {

    private var service: IRootMouseService? = null
    private var connected = false
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var flushJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var targetDisplayId = -1
    private var targetWidth = 0
    private var targetHeight = 0

    /** Connect to the root mouse service; returns true when bound. */
    suspend fun connect(): Boolean {
        if (connected) return true
        val binder = suspendCancellableCoroutine<android.os.IBinder?> { cont ->
            val intent = android.content.Intent(context, RootMouseService::class.java)
            var settled = false
            RootService.bind(
                intent,
                object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                        if (!settled) {
                            settled = true
                            cont.resume(binder)
                        }
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        if (!settled) {
                            settled = true
                            cont.resume(null)
                        }
                    }
                },
            )
        }
        service = binder?.let { IRootMouseService.Stub.asInterface(it) }
        connected = service != null
        if (connected && targetDisplayId >= 0) {
            service!!.setDisplay(targetDisplayId, targetWidth, targetHeight)
        }
        Log.i(TAG, "root mouse service connected=$connected")
        return connected
    }

    override suspend fun setTargetDisplay(displayId: Int, width: Int, height: Int) {
        targetDisplayId = displayId
        targetWidth = width
        targetHeight = height
        service?.setDisplay(displayId, width, height)
    }

    override suspend fun moveRelative(dx: Float, dy: Float) {
        pendingDx += dx
        pendingDy += dy
        if (flushJob == null) {
            flushJob = scope.launch {
                delay(16) // ~60 Hz IPC rate limit
                val s = service
                val x = pendingDx
                val y = pendingDy
                pendingDx = 0f
                pendingDy = 0f
                flushJob = null
                s?.moveMouse(x, y)
            }
        }
    }

    override suspend fun moveAbsolute(x: Float, y: Float) {
        service?.moveTo(x, y)
    }

    override suspend fun buttonDown(button: MouseButton) {
        service?.buttonDown(button.motionButton())
    }

    override suspend fun buttonUp(button: MouseButton) {
        service?.buttonUp(button.motionButton())
    }

    override suspend fun click(button: MouseButton, x: Float, y: Float) {
        val code = if (button == MouseButton.RIGHT) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        service?.click(x, y, code)
    }

    override suspend fun scroll(dx: Float, dy: Float) {
        service?.scroll(dx, dy)
    }

    override suspend fun key(keyCode: Int) {
        service?.pressKey(keyCode)
    }

    override suspend fun resetInputState() {
        service?.resetInputState()
    }

    override suspend fun close() {
        flushJob?.cancel()
        try {
            service?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "destroy failed: ${e.message}")
        }
        service = null
        connected = false
        Log.i(TAG, "closed")
    }

    private fun MouseButton.motionButton(): Int = when (this) {
        MouseButton.LEFT -> MotionEvent.BUTTON_PRIMARY
        MouseButton.RIGHT -> MotionEvent.BUTTON_SECONDARY
        MouseButton.MIDDLE -> MotionEvent.BUTTON_TERTIARY
    }

    private companion object {
        const val TAG = "UinputBackend"
    }
}
