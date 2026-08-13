package com.example.ar_glass_plus.input.mouse

import kotlin.math.hypot

/**
 * Maps touchpad finger deltas (dp) to cursor deltas. Kept pluggable so the
 * feel curve can be tuned after P2.6 profiling without touching the gesture
 * layer.
 */
fun interface PointerTransferFunction {
    /** @return scaled (dx, dy) in touchpad dp. */
    fun map(dx: Float, dy: Float, dtMillis: Long): Pair<Float, Float>
}

/** Constant gain — the P2.5.2A behavior. */
class LinearPointerTransfer(private val gain: Float = 1f) : PointerTransferFunction {
    override fun map(dx: Float, dy: Float, dtMillis: Long): Pair<Float, Float> =
        (dx * gain) to (dy * gain)
}

/**
 * Light speed-dependent gain (no curve fitting):
 *   slow  (<0.12 dp/ms) -> 0.7x   fine positioning
 *   normal(<0.5 dp/ms)  -> 1.1x   default feel
 *   fast  (>=0.5 dp/ms) -> 1.8x   quick sweeps across the content
 */
class AdaptivePointerTransfer(
    private val baseGain: Float = 1f,
) : PointerTransferFunction {

    override fun map(dx: Float, dy: Float, dtMillis: Long): Pair<Float, Float> {
        val speed = if (dtMillis > 0) hypot(dx.toDouble(), dy.toDouble()) / dtMillis else 0.0
        val gain = when {
            speed < 0.12 -> 0.7
            speed < 0.5 -> 1.1
            else -> 1.8
        } * baseGain
        return (dx * gain.toFloat()) to (dy * gain.toFloat())
    }
}
