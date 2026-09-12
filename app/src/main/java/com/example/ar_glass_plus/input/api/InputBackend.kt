package com.example.ar_glass_plus.input.api

/** Mouse buttons (MotionEvent constants). */
enum class MouseButton(val code: Int) {
    LEFT(1),
    RIGHT(2),
    MIDDLE(3),
}

enum class PointerAction {
    MOVE,
    DOWN,
    UP,
    CLICK,
    DOUBLE_CLICK,
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

    /** Move to an absolute content pixel without changing the target display. */
    suspend fun moveAbsolute(x: Float, y: Float)

    suspend fun buttonDown(button: MouseButton)

    suspend fun buttonUp(button: MouseButton)

    /** One down+up at the current pointer position. */
    suspend fun click(button: MouseButton, x: Float, y: Float)

    /** Scroll: finger-pixel deltas (positive = scroll down/right content). */
    suspend fun scroll(dx: Float, dy: Float)

    /** Key injection (e.g. KEYCODE_BACK) on the target display. */
    suspend fun key(keyCode: Int)

    /** Safe teardown: release every possibly-held button (no stuck LEFT/RIGHT). */
    suspend fun resetInputState()

    /** Release all resources (uinput device, service connection). */
    suspend fun close()
}
