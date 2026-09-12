package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.api.InputBackend
import com.example.ar_glass_plus.input.mouse.MouseController
import com.example.ar_glass_plus.input.touchpad.TrackpadGestureEngine
import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.api.PointerAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

class RealInputSession(
    private val backend: InputBackend,
    private val cursor: CursorController,
    private val mouse: MouseController,
    private val engine: TrackpadGestureEngine,
) : InputSession {
    private val mutex = Mutex()

    override suspend fun onContentReady(contentDisplayId: Int, size: ContentSize) = mutex.withLock {
        engine.cancel()
        mouse.releaseAllButtons()
        backend.setTargetDisplay(contentDisplayId, size.width, size.height)
        cursor.onVirtualDisplayCreated()
    }

    override suspend fun onPointer(
        action: PointerAction,
        button: MouseButton,
        contentX: Float,
        contentY: Float,
    ) = mutex.withLock {
        when (action) {
            PointerAction.MOVE -> {
                cursor.moveTo(contentX, contentY)
                backend.moveAbsolute(contentX, contentY)
            }
            PointerAction.DOWN -> {
                cursor.moveTo(contentX, contentY, pressed = true)
                backend.moveAbsolute(contentX, contentY)
                backend.buttonDown(button)
            }
            PointerAction.UP -> {
                cursor.moveTo(contentX, contentY, pressed = false)
                backend.moveAbsolute(contentX, contentY)
                backend.buttonUp(button)
            }
            PointerAction.CLICK -> {
                cursor.moveTo(contentX, contentY, pressed = false)
                backend.click(button, contentX, contentY)
            }
            PointerAction.DOUBLE_CLICK -> {
                cursor.moveTo(contentX, contentY, pressed = false)
                backend.click(button, contentX, contentY)
                delay(DOUBLE_CLICK_INTERVAL_MS)
                backend.click(button, contentX, contentY)
            }
        }
    }

    override suspend fun onScroll(dx: Float, dy: Float) = mutex.withLock {
        backend.scroll(dx, dy)
    }

    override suspend fun onContentGone() = mutex.withLock {
        engine.cancel()
        mouse.releaseAllButtons()
        cursor.onVirtualDisplayDestroyed()
    }

    override suspend fun dispose() = mutex.withLock {
        engine.cancel()
        mouse.releaseAllButtons()
        backend.close()
    }

    private companion object {
        const val DOUBLE_CLICK_INTERVAL_MS = 60L
    }
}
