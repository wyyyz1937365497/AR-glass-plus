package com.example.ar_glass_plus.input.mouse

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.api.InputBackend
import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.touchpad.TrackpadGesture
import com.example.ar_glass_plus.render.overlay.CursorOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Consumes semantic [TrackpadGesture]s and drives the [InputBackend] + content
 * cursor. Single place where gestures become mouse semantics:
 *
 *   Move            -> cursor.move (transfer function) + backend.moveRelative
 *   LeftClick       -> backend.click(LEFT)
 *   LeftDoubleClick -> two LEFT clicks (~60 ms apart)
 *   LeftDrag*       -> buttonDown(LEFT) / move / buttonUp(LEFT)
 *   RightClick      -> backend.click(RIGHT)
 *   Scroll          -> backend.scroll (SINGLE pixel->detent conversion in the
 *                       service; this layer never re-scales)
 *
 * All events carry the content display id via the backend's target display —
 * nothing here ever targets the tablet display.
 */
class MouseController(
    private val backend: InputBackend,
    private val cursor: CursorController,
    private val transfer: PointerTransferFunction = AdaptivePointerTransfer(),
    private val scope: CoroutineScope,
) {

    private var lastMoveTime = 0L

    private var padWidth = 0f
    private var padHeight = 0f

    /** Current touchpad surface size (dp). Called from onSizeChanged. */
    fun setPadSize(width: Float, height: Float) {
        padWidth = width
        padHeight = height
    }

    /** Structured log: gesture -> backend action. */
    fun onGesture(gesture: TrackpadGesture) {
        when (gesture) {
            is TrackpadGesture.Move -> move(gesture.dx, gesture.dy)
            TrackpadGesture.LeftClick -> click(MouseButton.LEFT)
            TrackpadGesture.LeftDoubleClick -> doubleClick(MouseButton.LEFT)
            TrackpadGesture.LeftDragStart -> dragStart(MouseButton.LEFT)
            is TrackpadGesture.LeftDragMove -> dragMove(gesture.dx, gesture.dy)
            TrackpadGesture.LeftDragEnd -> dragEnd(MouseButton.LEFT)
            TrackpadGesture.RightClick -> click(MouseButton.RIGHT)
            is TrackpadGesture.Scroll -> scroll(gesture.horizontal, gesture.vertical)
            TrackpadGesture.RightDragStart -> dragStart(MouseButton.RIGHT)
            is TrackpadGesture.RightDragMove -> dragMove(gesture.dx, gesture.dy)
            TrackpadGesture.RightDragEnd -> dragEnd(MouseButton.RIGHT)
        }
    }

    /** Dedicated BACK (sidebar button). */
    fun onBack() {
        scope.launch {
            backend.key(KeyEvent.KEYCODE_BACK)
            Log.i(TAG, "Input: gesture=BACK")
        }
    }

    /**
     * Safe teardown: release every possibly-held button. Called on session
     * teardown, display change, activity destroy — never leaves a stuck
     * LEFT/RIGHT in the content app.
     */
    suspend fun releaseAllButtons() {
        backend.resetInputState()
        cursor.setPressed(false)
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun move(dxPad: Float, dyPad: Float) {
        if (padWidth <= 0f || padHeight <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMoveTime > 0) now - lastMoveTime else 16L
        lastMoveTime = now
        val (mx, my) = transfer.map(dxPad, dyPad, dt)
        val (cdx, cdy) = cursor.move(mx, my, padWidth, padHeight)
        if (cdx != 0f || cdy != 0f) {
            scope.launch { backend.moveRelative(cdx, cdy) }
        }
    }

    private fun click(button: MouseButton) {
        val c = CursorOverlayState.cursor.value ?: return
        scope.launch {
            backend.click(button, c.x, c.y)
            Log.i(TAG, "click button=$button at=(${"%.1f".format(c.x)},${"%.1f".format(c.y)})")
        }
    }

    private fun doubleClick(button: MouseButton) {
        val c = CursorOverlayState.cursor.value ?: return
        scope.launch {
            backend.click(button, c.x, c.y)
            delay(60)
            backend.click(button, c.x, c.y)
            Log.i(TAG, "doubleClick button=$button at=(${"%.1f".format(c.x)},${"%.1f".format(c.y)})")
        }
    }

    private fun dragStart(button: MouseButton) {
        cursor.setPressed(true)
        scope.launch {
            backend.buttonDown(button)
            Log.i(TAG, "dragStart button=$button")
        }
    }

    private fun dragMove(dxPad: Float, dyPad: Float) {
        // Button is already held; movement streams cursor + backend move.
        move(dxPad, dyPad)
    }

    private fun dragEnd(button: MouseButton) {
        cursor.setPressed(false)
        scope.launch {
            backend.buttonUp(button)
            Log.i(TAG, "dragEnd button=$button")
        }
    }

    private fun scroll(dxPad: Float, dyPad: Float) {
        if (dxPad == 0f && dyPad == 0f) return
        scope.launch {
            backend.scroll(dxPad, dyPad)
        }
    }

    private companion object {
        const val TAG = "MouseCtrl"
    }
}
