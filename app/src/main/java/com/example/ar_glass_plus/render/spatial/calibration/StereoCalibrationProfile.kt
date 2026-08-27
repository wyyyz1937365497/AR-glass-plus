package com.example.ar_glass_plus.render.spatial.calibration

import com.example.ar_glass_plus.render.geometry.PixelRect

/** Which eye feeds the LEFT viewport (physical left half of the output). */
enum class EyeOrder {
    /** Left half of the screen shows the LEFT eye (normal). */
    LEFT_FIRST,

    /** Left half shows the RIGHT eye — glasses/media chain swapped. */
    RIGHT_FIRST,
}

/**
 * Optical/geometry profile of the stereo output. NOTHING here is assumed:
 * viewport split, eye order, per-eye principal point and clip planes are
 * all calibration inputs, because the physical glasses may crop, scale,
 * rotate or swap the signal before it reaches the eyes.
 *
 * Principal point (projectionCenter*) is in VIEWPORT pixels; it is where a
 * world point straight ahead of that eye should land. Deviations from the
 * viewport center expose optical de-centering.
 */
data class StereoCalibrationProfile(
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val leftViewport: PixelRect,
    val rightViewport: PixelRect,
    val eyeOrder: EyeOrder,
    val ipdMeters: Float,
    val projectionCenterXLeft: Float,
    val projectionCenterYLeft: Float,
    val projectionCenterXRight: Float,
    val projectionCenterYRight: Float,
    val nearMeters: Float,
    val farMeters: Float,
    val fovYDegrees: Float = DEFAULT_FOV_Y_DEGREES,
) {
    init {
        require(outputWidthPx > 0 && outputHeightPx > 0) { "bad output size" }
        require(leftViewport.width > 0f && leftViewport.height > 0f) { "bad left viewport" }
        require(rightViewport.width > 0f && rightViewport.height > 0f) { "bad right viewport" }
        require(ipdMeters > 0f) { "ipd must be positive" }
        require(nearMeters > 0f && farMeters > nearMeters) { "bad clip planes" }
    }

    companion object {
        const val DEFAULT_FOV_Y_DEGREES = 60f
        const val DEFAULT_IPD_METERS = 0.063f
        const val DEFAULT_NEAR_METERS = 0.05f
        const val DEFAULT_FAR_METERS = 20f

        /** Equal halves, left eye first, principal points at viewport centers. */
        fun defaultFor(outputWidthPx: Int, outputHeightPx: Int): StereoCalibrationProfile {
            val half = outputWidthPx / 2f
            val left = PixelRect(0f, 0f, half, outputHeightPx.toFloat())
            val right = PixelRect(half, 0f, outputWidthPx.toFloat(), outputHeightPx.toFloat())
            return StereoCalibrationProfile(
                outputWidthPx = outputWidthPx,
                outputHeightPx = outputHeightPx,
                leftViewport = left,
                rightViewport = right,
                eyeOrder = EyeOrder.LEFT_FIRST,
                ipdMeters = DEFAULT_IPD_METERS,
                projectionCenterXLeft = left.width / 2f,
                projectionCenterYLeft = left.height / 2f,
                projectionCenterXRight = right.width / 2f,
                projectionCenterYRight = right.height / 2f,
                nearMeters = DEFAULT_NEAR_METERS,
                farMeters = DEFAULT_FAR_METERS,
            )
        }
    }
}
