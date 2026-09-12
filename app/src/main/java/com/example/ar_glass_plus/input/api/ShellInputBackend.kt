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
    private var cursorX = 0f
    private var cursorY = 0f

    override suspend fun setTargetDisplay(displayId: Int, width: Int, height: Int) {
        this.displayId = displayId
    }

    override suspend fun moveRelative(dx: Float, dy: Float) {
        moveAbsolute(cursorX + dx, cursorY + dy)
    }

    override suspend fun moveAbsolute(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        if (displayId >= 0) {
            shell.exec("input -d $displayId motionevent MOVE ${x.toInt()} ${y.toInt()}")
        }
    }

    override suspend fun buttonDown(button: MouseButton) {
        if (displayId >= 0 && button == MouseButton.LEFT) {
            shell.exec("input -d $displayId motionevent DOWN ${cursorX.toInt()} ${cursorY.toInt()}")
        }
    }

    override suspend fun buttonUp(button: MouseButton) {
        if (displayId >= 0 && button == MouseButton.LEFT) {
            shell.exec("input -d $displayId motionevent UP ${cursorX.toInt()} ${cursorY.toInt()}")
        }
    }

    override suspend fun click(button: MouseButton, x: Float, y: Float) {
        if (displayId >= 0) shell.exec("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
    }

    override suspend fun scroll(dx: Float, dy: Float) {
        // Shell fallback: no scroll until it is needed (uinput is primary).
    }

    override suspend fun key(keyCode: Int) {
        if (displayId >= 0) shell.exec("input -d $displayId keyevent $keyCode")
    }

    override suspend fun resetInputState() = Unit

    override suspend fun close() = Unit
}
