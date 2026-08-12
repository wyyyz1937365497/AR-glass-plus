package com.example.ar_glass_plus.input

import android.util.Log
import android.view.MotionEvent
import com.example.ar_glass_plus.render.overlay.CursorOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Relative touchpad: finger Δ moves the cursor (content space); tap clicks the
 * cursor position; long-press + move becomes a drag injected as one swipe on
 * UP (per-gesture injection — never per MOVE). All pointer changes are
 * consumed so the touchpad NEVER scrolls the control UI.
 */
class RelativeTouchpadController(
    private val cursor: CursorController,
    private val injector: InputInjector,
    private val onContentId: () -> Int,
    private val scope: CoroutineScope,
) {

    private var gestureActive = false
    private var isDrag = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var injectionJob: Job? = null

    var padWidth: Float = 0f
    var padHeight: Float = 0f

    fun onTouch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                gestureActive = true
                isDrag = false
                downX = x
                downY = y
                lastX = x
                lastY = y
                downTime = System.currentTimeMillis()
                cursor.setPressed(true)
                val c = CursorOverlayState.cursor.value
                dragStartX = c?.x ?: 0f
                dragStartY = c?.y ?: 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                cursor.move(dx, dy, padWidth, padHeight)
                if (System.currentTimeMillis() - downTime > LONG_PRESS_MS) {
                    isDrag = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return
                gestureActive = false
                cursor.setPressed(false)
                finishGesture(x, y)
            }

            MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                isDrag = false
                cursor.setPressed(false)
            }
        }
    }

    /** Dedicated BACK for the sidebar. */
    fun onBack() {
        val id = onContentId()
        if (id < 0) {
            Log.w(TAG, "BACK rejected, no content display")
            return
        }
        injectionJob = scope.launch {
            val r = injector.key(id, android.view.KeyEvent.KEYCODE_BACK)
            Log.i(TAG, "Input: gesture=BACK contentDisplayId=$id result=${r.exitCode}")
        }
    }

    private fun finishGesture(x: Float, y: Float) {
        val id = onContentId()
        if (id < 0) {
            Log.w(TAG, "gesture cancelled, no content display")
            return
        }
        val c = CursorOverlayState.cursor.value ?: return
        val duration = System.currentTimeMillis() - downTime
        val moved = sqrt((x - downX) * (x - downX) + (y - downY) * (y - downY))

        if (isDrag && moved > DRAG_SLOP) {
            // Drag: one swipe from gesture-start cursor to current cursor.
            injectionJob = scope.launch {
                val r = injector.swipe(
                    id,
                    dragStartX,
                    dragStartY,
                    c.x,
                    c.y,
                    duration.coerceIn(80L, 1000L),
                )
                Log.i(TAG, "Input: gesture=DRAG content=(${"%.1f".format(dragStartX)},${"%.1f".format(dragStartY)})->(${"%.1f".format(c.x)},${"%.1f".format(c.y)}) contentDisplayId=$id result=${r.exitCode}")
            }
        } else if (!isDrag && moved <= CLICK_SLOP) {
            // Click at the CURRENT cursor position (never the pad touch point).
            injectionJob = scope.launch {
                val r = injector.tap(id, c.x, c.y)
                Log.i(TAG, "Input: gesture=CLICK content=(${"%.1f".format(c.x)},${"%.1f".format(c.y)}) contentDisplayId=$id result=${r.exitCode}")
            }
        }
    }

    private companion object {
        const val TAG = "RelTouchpad"
        const val LONG_PRESS_MS = 400L
        const val CLICK_SLOP = 12f
        const val DRAG_SLOP = 8f
    }
}
