package com.example.ar_glass_plus.input.api

import android.view.KeyEvent
import com.example.ar_glass_plus.root.RootShell

/**
 * Fallback backend: `input -d <displayId> ...` via root shell. One call per
 * gesture — retained as the reliable fallback when the uinput root service is
 * unavailable.
 */
class ShellInputBackend(private val shell: RootShell) : InputBackend {

    private var displayId = -1

    override suspend fun setTargetDisplay(displayId: Int, width: Int, height: Int) {
        this.displayId = displayId
    }

    override suspend fun moveRelative(dx: Float, dy: Float) {
        // Shell backend cannot do continuous relative motion well; no-op here
        // (absolute tap/swipe gestures are handled at the controller level).
    }

    override suspend fun buttonDown(button: MouseButton) = Unit
    override suspend fun buttonUp(button: MouseButton) = Unit

    override suspend fun click(button: MouseButton, x: Float, y: Float) {
        if (displayId >= 0) shell.exec("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
    }

    override suspend fun scroll(dx: Float, dy: Float) {
        // Not supported via shell; swipe fallback handled by controller.
    }

    override suspend fun scrollDrag(dy: Float, action: Int) = Unit

    override suspend fun key(keyCode: Int) {
        if (displayId >= 0) shell.exec("input -d $displayId keyevent $keyCode")
    }

    override suspend fun close() = Unit
}
