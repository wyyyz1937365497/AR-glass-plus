package com.example.ar_glass_plus.input.api

/** Mouse buttons (MotionEvent constants). */
enum class MouseButton(val code: Int) {
    LEFT(1),
    RIGHT(2),
    MIDDLE(3),
}

/**
 * Real-mouse input backend. Upper layers (touchpad) never know whether the
 * implementation is a uinput virtual mouse, a shell `input` command, or
 * something else.
 */
interface InputBackend {

    /** Bind the backend's pointer to a target display (content display). */
    suspend fun setTargetDisplay(displayId: Int, width: Int, height: Int)

    /** Relative pointer movement in content pixels (sub-pixel safe). */
    suspend fun moveRelative(dx: Float, dy: Float)

    suspend fun buttonDown(button: MouseButton)

    suspend fun buttonUp(button: MouseButton)

    /** One down+up at the current pointer position. */
    suspend fun click(button: MouseButton, x: Float, y: Float)

    /** Scroll: finger-pixel deltas (positive = scroll down content). */
    suspend fun scroll(dx: Float, dy: Float)

    /** Touch-drag scroll gesture (action: 0=DOWN, 1=MOVE, 2=UP). */
    suspend fun scrollDrag(dy: Float, action: Int)

    /** Key injection (e.g. KEYCODE_BACK) on the target display. */
    suspend fun key(keyCode: Int)

    /** Release all resources (uinput device, service connection). */
    suspend fun close()
}
