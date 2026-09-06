package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.render.spatial.calibration.EyeOrder
import com.example.ar_glass_plus.render.spatial.calibration.StereoCalibrationProfile

/**
 * Live-editable stereo calibration state for Gate 3R. Kept as an explicit
 * draft (viewports derived from output size + eye order; principal points,
 * IPD and FOV free-tuned) so the tablet panel can nudge values while the user
 * wears the glasses; the render host applies it every frame without
 * rebuilding anything.
 *
 * [applyTo] re-derives the full profile from the draft + current output
 * size. Principal points are always stored in a stable, eye-local
 * 1920x1080 reference space, never in full-output coordinates.
 */
data class CalibrationDraft(
    val eyeOrderLeftFirst: Boolean = true,
    val ipdMeters: Float = StereoCalibrationProfile.DEFAULT_IPD_METERS,
    val leftCenterX: Float = 960f,
    val leftCenterY: Float = 540f,
    val rightCenterX: Float = 960f,
    val rightCenterY: Float = 540f,
    val fovYDegrees: Float = StereoCalibrationProfile.DEFAULT_FOV_Y_DEGREES,
) {
    /** Step sizes for the live panel. */
    val ipdStep: Float get() = 0.0005f // 0.5mm
    val centerStep: Float get() = 2f

    fun nudgeIpd(delta: Float) = copy(ipdMeters = (ipdMeters + delta).coerceIn(MIN_IPD_METERS, MAX_IPD_METERS))
    fun nudgeLeft(dx: Float, dy: Float) = copy(
        leftCenterX = (leftCenterX + dx).coerceIn(0f, REFERENCE_EYE_WIDTH_PX),
        leftCenterY = (leftCenterY + dy).coerceIn(0f, REFERENCE_EYE_HEIGHT_PX),
    )
    fun nudgeRight(dx: Float, dy: Float) = copy(
        rightCenterX = (rightCenterX + dx).coerceIn(0f, REFERENCE_EYE_WIDTH_PX),
        rightCenterY = (rightCenterY + dy).coerceIn(0f, REFERENCE_EYE_HEIGHT_PX),
    )
    fun nudgeFov(deltaDegrees: Float) = copy(
        fovYDegrees = (fovYDegrees + deltaDegrees).coerceIn(MIN_FOV_Y_DEGREES, MAX_FOV_Y_DEGREES),
    )

    /** Sanitizes values loaded from persistent storage or direct text input. */
    fun normalized(): CalibrationDraft = copy(
        ipdMeters = ipdMeters.coerceIn(MIN_IPD_METERS, MAX_IPD_METERS),
        leftCenterX = leftCenterX.coerceIn(0f, REFERENCE_EYE_WIDTH_PX),
        leftCenterY = leftCenterY.coerceIn(0f, REFERENCE_EYE_HEIGHT_PX),
        rightCenterX = rightCenterX.coerceIn(0f, REFERENCE_EYE_WIDTH_PX),
        rightCenterY = rightCenterY.coerceIn(0f, REFERENCE_EYE_HEIGHT_PX),
        fovYDegrees = fovYDegrees.coerceIn(MIN_FOV_Y_DEGREES, MAX_FOV_Y_DEGREES),
    )

    /** Derives the full profile for a [width]x[height] output. */
    fun applyTo(width: Int, height: Int): StereoCalibrationProfile {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val half = w / 2f
        // Draft centers are stored for a 1920x1080-half reference; scale to
        // the actual half-viewport size.
        val draft = normalized()
        val scaleX = half / REFERENCE_EYE_WIDTH_PX
        val scaleY = h / REFERENCE_EYE_HEIGHT_PX
        return StereoCalibrationProfile(
            outputWidthPx = w,
            outputHeightPx = h,
            leftViewport = com.example.ar_glass_plus.render.geometry.PixelRect(0f, 0f, half, h.toFloat()),
            rightViewport = com.example.ar_glass_plus.render.geometry.PixelRect(half, 0f, w.toFloat(), h.toFloat()),
            eyeOrder = if (draft.eyeOrderLeftFirst) EyeOrder.LEFT_FIRST else EyeOrder.RIGHT_FIRST,
            ipdMeters = draft.ipdMeters,
            projectionCenterXLeft = draft.leftCenterX * scaleX,
            projectionCenterYLeft = draft.leftCenterY * scaleY,
            projectionCenterXRight = draft.rightCenterX * scaleX,
            projectionCenterYRight = draft.rightCenterY * scaleY,
            nearMeters = StereoCalibrationProfile.DEFAULT_NEAR_METERS,
            farMeters = StereoCalibrationProfile.DEFAULT_FAR_METERS,
            fovYDegrees = draft.fovYDegrees,
        )
    }

    /** Human-readable record for diagnostics and exported profiles. */
    fun dump(deviceName: String, width: Int, height: Int): String = buildString {
        appendLine("RayNeo Calibration Profile")
        appendLine("device: $deviceName")
        appendLine("output: ${width}x$height")
        appendLine("eyeOrder: ${if (eyeOrderLeftFirst) "LEFT_FIRST" else "RIGHT_FIRST"}")
        appendLine("ipdMeters: $ipdMeters")
        appendLine("leftCenter: ($leftCenterX, $leftCenterY)")
        appendLine("rightCenter: ($rightCenterX, $rightCenterY)")
        appendLine("fovYDegrees: $fovYDegrees")
    }

    companion object {
        const val REFERENCE_EYE_WIDTH_PX = 1920f
        const val REFERENCE_EYE_HEIGHT_PX = 1080f
        const val MIN_IPD_METERS = 0.055f
        const val MAX_IPD_METERS = 0.075f
        const val MIN_FOV_Y_DEGREES = 35f
        const val MAX_FOV_Y_DEGREES = 90f

        /** Draft defaults for one 1920x1080 eye viewport. */
        fun default(): CalibrationDraft = CalibrationDraft(
            eyeOrderLeftFirst = true,
            ipdMeters = StereoCalibrationProfile.DEFAULT_IPD_METERS,
            leftCenterX = 960f,
            leftCenterY = 540f,
            rightCenterX = 960f,
            rightCenterY = 540f,
            fovYDegrees = StereoCalibrationProfile.DEFAULT_FOV_Y_DEGREES,
        )
    }
}
