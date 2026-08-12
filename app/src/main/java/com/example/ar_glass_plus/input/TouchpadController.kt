package com.example.ar_glass_plus.input

import android.util.Log
import android.view.MotionEvent
import com.example.ar_glass_plus.render.geometry.RenderLayoutSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Absolute Control Pad (P2.5): one finger gesture -> one injection.
 *
 * Gestures: TAP (down+up, small movement), SWIPE (down+move+up with distance),
 * BACK (dedicated button on the pad). Gestures are injected only on UP, one
 * `input` call per gesture — never per MOVE. A gesture is cancelled if the
 * layout generation changes between DOWN and UP (geometry changed mid-gesture)
 * or if the content display id changed (stale target).
 */
class TouchpadController(
    private val mapper: InputMapper,
    private val injector: InputInjector,
    private val onContentId: () -> Int,
    private val onSnapshot: () -> RenderLayoutSnapshot?,
    private val scope: CoroutineScope,
) {

    private var gestureActive = false
    private var downGeneration: Long = -1
    private var downContentId: Int = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveMs = 0L
    private var injectionJob: Job? = null

    /** Pad size in pixels, updated by the UI. */
    var padWidth: Float = 0f
        set(value) {
            field = value
        }
    var padHeight: Float = 0f
        set(value) {
            field = value
        }

    /** Feed raw pad touch events (x/y in pad pixels). */
    fun onTouch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                gestureActive = true
                downX = x
                downY = y
                lastX = x
                lastY = y
                lastMoveMs = System.currentTimeMillis()
                downGeneration = onSnapshot()?.generation ?: -1
                downContentId = onContentId()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return
                lastX = x
                lastY = y
                lastMoveMs = System.currentTimeMillis()
            }

            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return
                gestureActive = false
                finishGesture(downX, downY, lastX, lastY)
            }

            MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
            }
        }
    }

    /** Dedicated BACK button on the pad. */
    fun onBack() {
        injectionJob?.cancel()
        val id = onContentId()
        if (id < 0) {
            Log.w(TAG, "Input: BACK rejected, no content display")
            return
        }
        injectionJob = scope.launch {
            val result = injector.key(id, android.view.KeyEvent.KEYCODE_BACK)
            Log.i(TAG, "Input: gesture=BACK contentDisplayId=$id result=${result.exitCode}")
        }
    }

    private fun finishGesture(x1: Float, y1: Float, x2: Float, y2: Float) {
        val snapshot = onSnapshot() ?: run {
            Log.w(TAG, "Input: rejected, no layout snapshot")
            return
        }
        val contentId = onContentId()
        val generation = snapshot.generation

        // Stale target or layout changed mid-gesture -> drop.
        if (contentId != downContentId) {
            Log.w(TAG, "Input: cancelled, contentDisplayId changed $downContentId -> $contentId")
            return
        }
        if (generation != downGeneration) {
            Log.w(TAG, "Input: cancelled, layout generation changed $downGeneration -> $generation")
            return
        }

        val dx = x2 - x1
        val dy = y2 - y1
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val duration = System.currentTimeMillis() - lastMoveMs

        val from = mapper.map(x1, y1, padWidth, padHeight, snapshot)
        if (from.rejected) {
            mapper.logChain(TAG, from, snapshot, contentId, "TAP", -1)
            return
        }
        val fromContent = from.contentPoint!!

        if (distance < SWIPE_THRESHOLD) {
            // TAP
            injectionJob = scope.launch {
                val result = injector.tap(contentId, fromContent.x, fromContent.y)
                mapper.logChain(TAG, from, snapshot, contentId, "TAP", result.exitCode)
            }
        } else {
            val to = mapper.map(x2, y2, padWidth, padHeight, snapshot)
            if (to.rejected) {
                mapper.logChain(TAG, to, snapshot, contentId, "SWIPE", -1)
                return
            }
            injectionJob = scope.launch {
                val result = injector.swipe(
                    contentId,
                    fromContent.x,
                    fromContent.y,
                    to.contentPoint!!.x,
                    to.contentPoint.y,
                    duration.coerceIn(50L, 800L),
                )
                mapper.logChain(TAG, from, snapshot, contentId, "SWIPE", result.exitCode)
            }
        }
    }

    private companion object {
        const val TAG = "Touchpad"
        const val SWIPE_THRESHOLD = 24f
    }
}
