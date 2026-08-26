package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.api.InputBackend
import com.example.ar_glass_plus.input.mouse.MouseController
import com.example.ar_glass_plus.input.touchpad.TrackpadGestureEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
}
