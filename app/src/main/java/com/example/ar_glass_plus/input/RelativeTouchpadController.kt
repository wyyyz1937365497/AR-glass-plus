package com.example.ar_glass_plus.input

import android.util.Log
import android.view.MotionEvent
import com.example.ar_glass_plus.input.api.InputBackend
import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.render.overlay.CursorOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Relative touchpad with standard tap-to-click semantics:
 *   - Finger down + move      -> moves the cursor only (NO button held)
 *   - Quick tap               -> click (buttonDown+up at cursor)
 *   - Long press (no move)    -> buttonDown -> drag (move while held = drag)
 *   - Two-finger scroll       -> touch-drag scroll in the content list zone
 * All pointer changes are consumed; the touchpad NEVER scrolls the control UI.
 */
class RelativeTouchpadController(
    private val cursor: CursorController,
    private val backend: InputBackend,
    private val onContentId: () -> Int,
    private val scope: CoroutineScope,
) {

    private var gestureActive = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var longPressJob: Job? = null
    private var keyJob: Job? = null

    // Two-finger scroll state
    private var scrollActive = false
    private var scrollAccum = 0f
    private var scrollChain: Job? = null

    var padWidth: Float = 0f
    var padHeight: Float = 0f

    fun onTouch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                gestureActive = true
                dragging = false
                downX = x
                downY = y
                lastX = x
                lastY = y
                downTime = System.currentTimeMillis()
                cursor.setPressed(false)
                // Long press -> start a drag (button held down).
                longPressJob = scope.launch {
                    delay(LONG_PRESS_MS)
                    if (gestureActive) {
                        dragging = true
                        cursor.setPressed(true)
                        backend.buttonDown(MouseButton.LEFT)
                        Log.i(TAG, "drag start contentDisplayId=${onContentId()}")
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                val (cdx, cdy) = cursor.move(dx, dy, padWidth, padHeight)
                scope.launch { backend.moveRelative(cdx, cdy) }
                // Moving before long-press cancels the tap/drag candidate.
                val moved = sqrt((x - downX) * (x - downX) + (y - downY) * (y - downY))
                if (!dragging && moved > DRAG_SLOP) {
                    longPressJob?.cancel()
                    longPressJob = null
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return
                gestureActive = false
                longPressJob?.cancel()
                longPressJob = null
                val moved = sqrt((x - downX) * (x - downX) + (y - downY) * (y - downY))
                val duration = System.currentTimeMillis() - downTime
                val c = CursorOverlayState.cursor.value

                if (dragging) {
                    cursor.setPressed(false)
                    scope.launch { backend.buttonUp(MouseButton.LEFT) }
                    Log.i(TAG, "drag end content=(${"%.1f".format(c?.x ?: 0f)},${"%.1f".format(c?.y ?: 0f)}) contentDisplayId=${onContentId()}")
                } else if (moved <= CLICK_SLOP && duration <= CLICK_MAX_MS) {
                    // Quick tap: click at the CURRENT cursor position.
                    scope.launch {
                        backend.click(MouseButton.LEFT, c?.x ?: 0f, c?.y ?: 0f)
                        Log.i(TAG, "click content=(${"%.1f".format(c?.x ?: 0f)},${"%.1f".format(c?.y ?: 0f)}) contentDisplayId=${onContentId()}")
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                longPressJob?.cancel()
                longPressJob = null
                if (dragging) {
                    dragging = false
                    cursor.setPressed(false)
                    scope.launch { backend.buttonUp(MouseButton.LEFT) }
                }
            }
        }
    }

    /** Second finger landed: cancel any pending press and start scroll-drag. */
    fun onMultiTouchStart() {
        longPressJob?.cancel()
        longPressJob = null
        if (dragging) {
            dragging = false
            cursor.setPressed(false)
            scope.launch { backend.buttonUp(MouseButton.LEFT) }
        }
        scrollActive = true
        scrollAccum = 0f
        scrollChain = scope.launch { backend.scrollDrag(0f, 0) }
    }

    /** All fingers lifted: end scroll-drag. */
    fun onMultiTouchEnd() {
        if (!scrollActive) return
        scrollActive = false
        scrollChain = scrollChain?.let { prev ->
            scope.launch { prev.join(); backend.scrollDrag(scrollAccum, 2) }
        } ?: scope.launch { backend.scrollDrag(scrollAccum, 2) }
        scrollAccum = 0f
    }

    /** Two-finger scroll deltas — serialized so AIDL calls stay ordered. */
    fun onScroll(dx: Float, dy: Float) {
        if (!scrollActive) return
        scrollAccum += dy
        val delta = dy
        scrollChain = scrollChain?.let { prev ->
            scope.launch { prev.join(); backend.scrollDrag(delta, 1) }
        } ?: scope.launch { backend.scrollDrag(delta, 1) }
    }

    /** Dedicated BACK from the sidebar. */
    fun onBack() {
        keyJob = scope.launch {
            backend.key(android.view.KeyEvent.KEYCODE_BACK)
            Log.i(TAG, "Input: gesture=BACK contentDisplayId=${onContentId()}")
        }
    }

    private companion object {
        const val TAG = "RelTouchpad"
        const val LONG_PRESS_MS = 400L
        const val CLICK_SLOP = 24f
        const val DRAG_SLOP = 16f
        const val CLICK_MAX_MS = 350L
    }
}
