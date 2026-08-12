package com.example.ar_glass_plus.input

import android.util.Log
import com.example.ar_glass_plus.render.overlay.CursorOverlayState

/**
 * Owns the cursor in CONTENT coordinates (the hidden VirtualDisplay space).
 * Movement is normalized by pad size and scaled by content size × sensitivity
 * so feel is stable across pad sizes. Clamped to content bounds — letterbox
 * never matters because the cursor never leaves content space. Resets to
 * center when a new VirtualDisplay is created. Publishes to
 * CursorOverlayState; the renderer (any backend) consumes it.
 */
class CursorController(
    private val onContentSize: () -> Pair<Int, Int>?,
) {

    private var sensitivity = DEFAULT_SENSITIVITY

    fun setSensitivity(value: Float) {
        sensitivity = value
    }

    fun onVirtualDisplayCreated() {
        center()
        Log.i(TAG, "cursor centered on new content display")
    }

    fun onVirtualDisplayDestroyed() {
        CursorOverlayState.setCursor(null)
    }

    fun center() {
        val (w, h) = onContentSize() ?: return
        CursorOverlayState.setCursor(CursorState(x = w / 2f, y = h / 2f, visible = true))
    }

    /**
     * Relative movement from the pad: dxContent = dxPad/padWidth * contentWidth * sensitivity.
     * Returns the CONTENT delta so the input backend can accumulate the same
     * cursor position (client and service must stay in sync).
     */
    fun move(dxPad: Float, dyPad: Float, padWidth: Float, padHeight: Float): Pair<Float, Float> {
        val (w, h) = onContentSize() ?: return 0f to 0f
        if (padWidth <= 0f || padHeight <= 0f) return 0f to 0f
        val cur = CursorOverlayState.cursor.value ?: return 0f to 0f
        val dx = dxPad / padWidth * w * sensitivity
        val dy = dyPad / padHeight * h * sensitivity
        CursorOverlayState.setCursor(
            cur.copy(
                x = (cur.x + dx).coerceIn(0f, w - 1f),
                y = (cur.y + dy).coerceIn(0f, h - 1f),
                visible = true,
            ),
        )
        return dx to dy
    }

    /** Button state for the overlay (pressed rendering). */
    fun setPressed(pressed: Boolean) {
        val cur = CursorOverlayState.cursor.value ?: return
        CursorOverlayState.setCursor(cur.copy(pressed = pressed))
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 1f
        private const val TAG = "CursorCtrl"
    }
}
