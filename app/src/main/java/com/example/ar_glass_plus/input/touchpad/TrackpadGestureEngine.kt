package com.example.ar_glass_plus.input.touchpad

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Explicit-state-machine trackpad recognizer (P2.5.2B).
 *
 * Consumes raw pointer events (finger id, position, event time) and emits
 * semantic [TrackpadGesture]s. Knows NOTHING about displays, backends,
 * cursors or injection — that is [com.example.ar_glass_plus.input.mouse.MouseController]'s job.
 *
 * State model (each finger-count change is an explicit transition):
 *   IDLE
 *    ├─ 1st finger down ──→ ONE_PENDING ──(slop exceeded)──→ ONE_MOVING
 *    │                         │ up (fast)                    │ up
 *    │                         ▼                              ▼
 *    │                     ONE_TAP_WAIT                    IDLE (no click)
 *    │                         │ 2nd down (in slop)
 *    │                         ▼
 *    │                     ONE_TAP2_HOLD ──(move/long-press)──→ ONE_DRAG
 *    │                         │ up (no move)                     │ up
 *    │                         ▼                                 ▼
 *    │                     IDLE (double click)               IDLE
 *    │
 *    └─ 2 fingers down ──→ TWO_PENDING ──(slop exceeded)──→ SCROLLING
 *                            │ all up                          │ any up
 *                            ▼                                 ▼
 *                        TWO_TAP_WAIT                       IDLE (no click)
 *                            │ 2nd two-finger down
 *                            ▼
 *                        TWO_TAP2_HOLD ──(move/long-press)──→ TWO_DRAG
 *                            │ up                                  │ up
 *                            ▼                                     ▼
 *                        IDLE (right dbl)                     IDLE
 *
 * Hard invariants (acceptance):
 *  - A gesture that reaches ONE_MOVING or SCROLLING can NEVER emit a click
 *    afterwards (move locks out click).
 *  - A drag holds the button: any cancel MUST emit DragEnd so the consumer
 *    releases the button (no stuck LEFT/RIGHT).
 */
class TrackpadGestureEngine(
    private val config: TrackpadConfig,
    private val scope: CoroutineScope,
) {

    private enum class State {
        IDLE, ONE_PENDING, ONE_MOVING, ONE_TAP_WAIT, ONE_TAP2_HOLD, ONE_DRAG,
        TWO_PENDING, SCROLLING, TWO_TAP_WAIT, TWO_TAP2_HOLD, TWO_DRAG,
    }

    private var state = State.IDLE

    // Per-gesture bookkeeping.
    private data class Finger(val x: Float, val y: Float)
    private val fingers = mutableMapOf<Long, Finger>()
    private var anchorX = 0f        // press anchor (down1 / tap2 / two-finger)
    private var anchorY = 0f
    private var anchorTime = 0L
    private var lastX = 0f          // last move position (for move deltas)
    private var lastY = 0f
    private var lastTime = 0L
    private var tap1X = 0f          // first tap position (double-tap window)
    private var tap1Y = 0f
    private var tap1Time = 0L
    private var timeoutJob: Job? = null
    private var holdJob: Job? = null

    private val out = mutableListOf<TrackpadGesture>()

    // ── input ─────────────────────────────────────────────────────────────

    /** A finger pressed. Returns gestures produced by the transition. */
    fun onPointerDown(id: Long, x: Float, y: Float, time: Long): List<TrackpadGesture> {
        out.clear()
        fingers[id] = Finger(x, y)
        when (state) {
            State.IDLE -> {
                if (fingers.size == 1) {
                    beginOneFinger(x, y, time)
                } else if (fingers.size == 2) {
                    // Two fingers landed within one frame: two-finger tap.
                    beginTwoFinger(x, y, time)
                }
            }

            State.ONE_PENDING, State.ONE_MOVING -> {
                if (fingers.size == 2) beginTwoFinger(x, y, time)
            }

            State.ONE_TAP_WAIT -> {
                if (hypot(x - tap1X, y - tap1Y) <= config.doubleTapSlop) {
                    // Second tap press: hold = drag, up = double click.
                    timeoutJob?.cancel()
                    state = State.ONE_TAP2_HOLD
                    anchorX = x
                    anchorY = y
                    anchorTime = time
                    lastX = x
                    lastY = y
                    scheduleHold { beginLeftDrag() }
                } else {
                    // Press far from the first tap: brand-new single-finger gesture.
                    timeoutJob?.cancel()
                    beginOneFinger(x, y, time)
                }
            }

            State.ONE_DRAG -> {
                if (fingers.size == 2) {
                    // Second finger lands mid-drag: end drag, switch to two-finger.
                    emit(TrackpadGesture.LeftDragEnd)
                    beginTwoFinger(x, y, time)
                }
            }

            State.TWO_PENDING, State.SCROLLING -> {
                // Third finger: out of scope, ignore.
                if (fingers.size > 2) fingers.remove(id)
            }

            State.TWO_TAP_WAIT -> {
                if (fingers.size == 2 && withinDoubleTap(x, y)) {
                    timeoutJob?.cancel()
                    state = State.TWO_TAP2_HOLD
                    anchorX = x
                    anchorY = y
                    anchorTime = time
                    lastX = x
                    lastY = y
                    scheduleHold { beginRightDrag() }
                } else if (fingers.size == 2) {
                    // Two fingers down but far from the tap: fresh two-finger gesture.
                    timeoutJob?.cancel()
                    beginTwoFinger(x, y, time)
                } else {
                    // Single finger down after a two-finger tap: fresh one-finger.
                    timeoutJob?.cancel()
                    beginOneFinger(x, y, time)
                }
            }

            State.ONE_TAP2_HOLD, State.TWO_TAP2_HOLD, State.TWO_DRAG -> {
                // Extra finger mid second-tap/drag: restart as two-finger.
                if (fingers.size == 2) beginTwoFinger(x, y, time)
            }
        }
        return flush()
    }

    /** A finger moved. `dx/dy` is this event's delta; `x/y` is absolute. */
    fun onPointerMove(
        id: Long,
        dx: Float,
        dy: Float,
        x: Float,
        y: Float,
        time: Long,
    ): List<TrackpadGesture> {
        out.clear()
        val prev = fingers[id] ?: return emptyList()
        fingers[id] = Finger(x, y)
        val eventDx = x - prev.x
        val eventDy = y - prev.y
        when (state) {
            State.ONE_PENDING -> {
                if (hypot(x - anchorX, y - anchorY) > config.touchSlop) {
                    // Exceeded slop: the whole accumulated travel becomes cursor
                    // movement, then keep streaming deltas.
                    state = State.ONE_MOVING
                    emit(TrackpadGesture.Move(x - anchorX, y - anchorY))
                    lastX = x
                    lastY = y
                    lastTime = time
                }
            }

            State.ONE_MOVING -> {
                emit(TrackpadGesture.Move(eventDx, eventDy))
                lastX = x
                lastY = y
                lastTime = time
            }

            State.ONE_TAP2_HOLD -> {
                if (hypot(x - anchorX, y - anchorY) > config.touchSlop) {
                    holdJob?.cancel()
                    beginLeftDrag()
                    emit(TrackpadGesture.LeftDragMove(x - anchorX, y - anchorY))
                    lastX = x
                    lastY = y
                    lastTime = time
                }
            }

            State.ONE_DRAG -> {
                emit(TrackpadGesture.LeftDragMove(eventDx, eventDy))
                lastX = x
                lastY = y
                lastTime = time
            }

            State.TWO_PENDING -> {
                if (fingers.size == 2 && centroidDist() > config.touchSlop) {
                    state = State.SCROLLING
                    val (cdx, cdy) = centroidDelta()
                    if (cdx != 0f || cdy != 0f) emit(TrackpadGesture.Scroll(cdx, cdy))
                } else if (
                    fingers.size == 1 &&
                    hypot(x - anchorX, y - anchorY) > config.touchSlop
                ) {
                    // The other finger lifted; this one keeps moving: plain
                    // cursor movement (move-lock — never a click).
                    state = State.ONE_MOVING
                    emit(TrackpadGesture.Move(x - anchorX, y - anchorY))
                    lastX = x
                    lastY = y
                    lastTime = time
                }
            }

            State.SCROLLING -> {
                if (fingers.size == 2) {
                    val (cdx, cdy) = centroidDelta()
                    if (cdx != 0f || cdy != 0f) emit(TrackpadGesture.Scroll(cdx, cdy))
                }
            }

            State.TWO_TAP2_HOLD -> {
                if (fingers.size == 2 && centroidDist() > config.touchSlop) {
                    holdJob?.cancel()
                    beginRightDrag()
                    val (cdx, cdy) = centroidDelta()
                    emit(TrackpadGesture.RightDragMove(cdx, cdy))
                }
            }

            State.TWO_DRAG -> {
                if (fingers.size == 2) {
                    val (cdx, cdy) = centroidDelta()
                    emit(TrackpadGesture.RightDragMove(cdx, cdy))
                }
            }

            else -> Unit
        }
        return flush()
    }

    /** A finger was released. */
    fun onPointerUp(id: Long, x: Float, y: Float, time: Long): List<TrackpadGesture> {
        out.clear()
        fingers.remove(id)
        when (state) {
            State.IDLE -> Unit

            State.ONE_PENDING -> {
                val elapsed = time - anchorTime
                if (elapsed <= config.doubleTapTimeout) {
                    emit(TrackpadGesture.LeftClick)
                    tap1X = x
                    tap1Y = y
                    tap1Time = time
                    state = State.ONE_TAP_WAIT
                    scheduleTimeout()
                } else {
                    state = State.IDLE
                }
            }

            State.ONE_MOVING -> state = if (fingers.isEmpty()) State.IDLE else State.ONE_MOVING

            State.ONE_TAP_WAIT -> {
                // Second finger up while waiting for second tap: keep waiting.
                if (fingers.isEmpty()) state = State.IDLE
            }

            State.ONE_TAP2_HOLD -> {
                holdJob?.cancel()
                val moved = hypot(x - anchorX, y - anchorY) <= config.touchSlop
                val fast = time - anchorTime <= config.doubleTapTimeout
                if (moved && fast) {
                    emit(TrackpadGesture.LeftDoubleClick)
                }
                state = if (fingers.isEmpty()) State.IDLE else State.ONE_TAP2_HOLD
            }

            State.ONE_DRAG -> {
                emit(TrackpadGesture.LeftDragEnd)
                state = if (fingers.isEmpty()) State.IDLE else State.ONE_DRAG
            }

            State.TWO_PENDING -> {
                if (fingers.isEmpty()) {
                    // Both fingers lifted: two-finger tap = right click.
                    emit(TrackpadGesture.RightClick)
                    tap1X = x
                    tap1Y = y
                    tap1Time = time
                    state = State.TWO_TAP_WAIT
                    scheduleTimeout()
                }
                // One finger remaining: STAY TWO_PENDING — the right-click
                // completes when the LAST finger lifts. Moving the remaining
                // finger still exceeds slop -> SCROLLING (see move handler),
                // which locks out the click.
            }

            State.SCROLLING -> {
                // Scroll end NEVER clicks (state lock). One finger remaining
                // becomes pure cursor movement, still no click.
                state = if (fingers.isEmpty()) State.IDLE else State.ONE_MOVING
            }

            State.TWO_TAP_WAIT -> {
                if (fingers.isEmpty()) state = State.IDLE
            }

            State.TWO_TAP2_HOLD -> {
                holdJob?.cancel()
                val moved = centroidDist() <= config.touchSlop
                val fast = time - anchorTime <= config.doubleTapTimeout
                if (moved && fast) {
                    emit(TrackpadGesture.RightClick) // second right click → right double click
                }
                state = if (fingers.isEmpty()) State.IDLE else State.TWO_TAP2_HOLD
            }

            State.TWO_DRAG -> {
                emit(TrackpadGesture.RightDragEnd)
                state = if (fingers.isEmpty()) State.IDLE else State.TWO_DRAG
            }
        }
        return flush()
    }

    /**
     * Abort the current gesture (content display changed, session torn down,
     * activity disposed). Emits DragEnd for any held button so the consumer
     * can release it — never leaves a stuck LEFT/RIGHT.
     */
    fun cancel(): List<TrackpadGesture> {
        out.clear()
        when (state) {
            State.ONE_DRAG -> emit(TrackpadGesture.LeftDragEnd)
            State.TWO_DRAG -> emit(TrackpadGesture.RightDragEnd)
            else -> Unit
        }
        resetInternal()
        return flush()
    }

    val isActive: Boolean
        get() = state != State.IDLE

    // ── transitions ────────────────────────────────────────────────────────

    private fun beginOneFinger(x: Float, y: Float, time: Long) {
        state = State.ONE_PENDING
        anchorX = x
        anchorY = y
        anchorTime = time
        lastX = x
        lastY = y
        lastTime = time
    }

    private fun beginTwoFinger(x: Float, y: Float, time: Long) {
        state = State.TWO_PENDING
        anchorX = x
        anchorY = y
        anchorTime = time
        lastX = x
        lastY = y
        lastTime = time
    }

    private fun beginLeftDrag() {
        state = State.ONE_DRAG
        emit(TrackpadGesture.LeftDragStart)
    }

    private fun beginRightDrag() {
        state = State.TWO_DRAG
        emit(TrackpadGesture.RightDragStart)
    }

    private fun scheduleTimeout() {
        timeoutJob = scope.launch {
            delay(config.doubleTapTimeout)
            if (state == State.ONE_TAP_WAIT || state == State.TWO_TAP_WAIT) {
                state = State.IDLE
            }
        }
    }

    private fun scheduleHold(action: () -> Unit) {
        holdJob = scope.launch {
            delay(config.longPressTimeout)
            if (state == State.ONE_TAP2_HOLD || state == State.TWO_TAP2_HOLD) {
                action()
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun withinDoubleTap(x: Float, y: Float): Boolean =
        hypot(x - tap1X, y - tap1Y) <= config.doubleTapSlop

    /** Distance of the finger centroid from the two-finger anchor. */
    private fun centroidDist(): Float {
        val (cx, cy) = centroid()
        return hypot(cx - anchorX, cy - anchorY)
    }

    /** Movement of the finger centroid since the previous event (avg delta). */
    private fun centroidDelta(): Pair<Float, Float> {
        val values = fingers.values.toList()
        if (values.isEmpty()) return 0f to 0f
        val cx = values.sumOf { it.x.toDouble() }.toFloat() / values.size
        val cy = values.sumOf { it.y.toDouble() }.toFloat() / values.size
        // lastX/lastY hold the previous centroid.
        val dx = cx - lastX
        val dy = cy - lastY
        lastX = cx
        lastY = cy
        return dx to dy
    }

    private fun centroid(): Pair<Float, Float> {
        val values = fingers.values.toList()
        if (values.isEmpty()) return lastX to lastY
        return (values.sumOf { it.x.toDouble() }.toFloat() / values.size) to
            (values.sumOf { it.y.toDouble() }.toFloat() / values.size)
    }

    private fun emit(gesture: TrackpadGesture) {
        out.add(gesture)
    }

    private fun flush(): List<TrackpadGesture> {
        val result = out.toList()
        out.clear()
        return result
    }

    private fun resetInternal() {
        state = State.IDLE
        fingers.clear()
        timeoutJob?.cancel()
        timeoutJob = null
        holdJob?.cancel()
        holdJob = null
    }

    private companion object {
        const val TAG = "GestureEngine"
    }
}
