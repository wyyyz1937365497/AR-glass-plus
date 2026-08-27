package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.render.spatial.calibration.EyeOrder
import com.example.ar_glass_plus.render.spatial.calibration.StereoCalibrationProfile

/**
 * Live-editable stereo calibration state for Gate 3R. Kept as an explicit
 * draft (viewports derived from output size + eye order; principal points
 * and IPD free-tuned) so the tablet panel can nudge values while the user
 * wears the glasses; the render host applies it every frame without
 * rebuilding anything.
 *
 * [applyTo] re-derives the full profile from the draft + current output
 * size; [dump] emits the human-readable record to persist (JSON later;
 * logcat text now — see Gate 3R notes).
 */
data class CalibrationDraft(
    val eyeOrderLeftFirst: Boolean = true,
    val ipdMeters: Float = StereoCalibrationProfile.DEFAULT_IPD_METERS,
    val leftCenterX: Float = 960f,
    val leftCenterY: Float = 540f,
    val rightCenterX: Float = 1440f,
    val rightCenterY: Float = 540f,
) {
    /** Step sizes for the live panel. */
    val ipdStep: Float get() = 0.0005f // 0.5mm
    val centerStep: Float get() = 2f

    fun nudgeIpd(delta: Float) = copy(ipdMeters = (ipdMeters + delta).coerceIn(0.055f, 0.075f))
    fun nudgeLeft(dx: Float, dy: Float) = copy(
        leftCenterX = (leftCenterX + dx).coerceIn(0f, 1920f),
        leftCenterY = (leftCenterY + dy).coerceIn(0f, 1080f),
    )
    fun nudgeRight(dx: Float, dy: Float) = copy(
        rightCenterX = (rightCenterX + dx).coerceIn(960f, 2880f),
        rightCenterY = (rightCenterY + dy).coerceIn(0f, 1080f),
    )

    /** Derives the full profile for a [width]x[height] output. */
    fun applyTo(width: Int, height: Int): StereoCalibrationProfile {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val half = w / 2f
        // Draft centers are stored for a 1920x1080-half reference; scale to
        // the actual half-viewport size.
        val scaleX = half / 960f
        val scaleY = h / 1080f
        return StereoCalibrationProfile(
            outputWidthPx = w,
            outputHeightPx = h,
            leftViewport = com.example.ar_glass_plus.render.geometry.PixelRect(0f, 0f, half, h.toFloat()),
            rightViewport = com.example.ar_glass_plus.render.geometry.PixelRect(half, 0f, w.toFloat(), h.toFloat()),
            eyeOrder = if (eyeOrderLeftFirst) EyeOrder.LEFT_FIRST else EyeOrder.RIGHT_FIRST,
            ipdMeters = ipdMeters,
            projectionCenterXLeft = leftCenterX * scaleX,
            projectionCenterYLeft = leftCenterY * scaleY,
            projectionCenterXRight = (rightCenterX - 960f) * scaleX, // draft is in 0..1920 right-half px
            projectionCenterYRight = rightCenterY * scaleY,
            nearMeters = StereoCalibrationProfile.DEFAULT_NEAR_METERS,
            farMeters = StereoCalibrationProfile.DEFAULT_FAR_METERS,
        )
    }

    /** Human-readable record (logcat now; JSON persistence in a later gate). */
    fun dump(deviceName: String, width: Int, height: Int): String = buildString {
        appendLine("RayNeo Calibration Profile")
        appendLine("device: $deviceName")
        appendLine("output: ${width}x$height")
        appendLine("eyeOrder: ${if (eyeOrderLeftFirst) "LEFT_FIRST" else "RIGHT_FIRST"}")
        appendLine("ipdMeters: $ipdMeters")
        appendLine("leftCenter: ($leftCenterX, $leftCenterY)")
        appendLine("rightCenter: ($rightCenterX, $rightCenterY)")
    }

    companion object {
        /** Draft defaults for the 1920x1080 overlay (centers at half-mids). */
        fun default(): CalibrationDraft = CalibrationDraft(
            eyeOrderLeftFirst = true,
            ipdMeters = StereoCalibrationProfile.DEFAULT_IPD_METERS,
            leftCenterX = 960f,
            leftCenterY = 540f,
            rightCenterX = 1440f,
            rightCenterY = 540f,
        )
    }
}
