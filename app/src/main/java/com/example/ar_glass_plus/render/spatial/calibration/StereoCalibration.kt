package com.example.ar_glass_plus.render.spatial.calibration

import com.example.ar_glass_plus.render.geometry.PixelRect
import com.example.ar_glass_plus.render.geometry.PixelPoint
import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.SpatialProjection
import com.example.ar_glass_plus.render.spatial.StereoCamera

/**
 * Builds per-eye render parameters (viewport, projection, camera) from a
 * [StereoCalibrationProfile] and the current head pose. The projection of
 * each eye carries that eye's principal-point offset so a world point
 * straight ahead of the eye lands exactly on the calibrated center.
 */
object StereoCalibration {

    /** One eye's full render setup for one frame. */
    data class EyeParams(
        val viewport: PixelRect,
        val projection: Mat4,
        val camera: SpatialCamera,
        val viewProjection: Mat4,
        /** Principal point in viewport px — the cross-check anchor. */
        val principalPoint: PixelPoint,
    )

    fun leftEye(profile: StereoCalibrationProfile, stereo: StereoCamera): EyeParams =
        eyeParams(profile.leftViewport, stereo.leftEye, profile.projectionCenterXLeft, profile.projectionCenterYLeft, profile)

    fun rightEye(profile: StereoCalibrationProfile, stereo: StereoCamera): EyeParams =
        eyeParams(profile.rightViewport, stereo.rightEye, profile.projectionCenterXRight, profile.projectionCenterYRight, profile)

    /** Viewport order for rendering: first the physical LEFT half. */
    fun renderEyes(profile: StereoCalibrationProfile, stereo: StereoCamera): List<EyeParams> =
        when (profile.eyeOrder) {
            EyeOrder.LEFT_FIRST -> listOf(leftEye(profile, stereo), rightEye(profile, stereo))
            EyeOrder.RIGHT_FIRST -> listOf(rightEye(profile, stereo), leftEye(profile, stereo))
        }

    fun monoEye(profile: StereoCalibrationProfile, camera: SpatialCamera): EyeParams =
        eyeParams(
            PixelRect(0f, 0f, profile.outputWidthPx.toFloat(), profile.outputHeightPx.toFloat()),
            camera,
            profile.outputWidthPx / 2f,
            profile.outputHeightPx / 2f,
            profile,
        )

    private fun eyeParams(
        viewport: PixelRect,
        camera: SpatialCamera,
        centerX: Float,
        centerY: Float,
        profile: StereoCalibrationProfile,
    ): EyeParams {
        val vw = viewport.width
        val vh = viewport.height
        require(vw > 0f && vh > 0f) { "empty viewport" }
        val aspect = vw / vh
        // Principal offset in NDC units: a point that the plain perspective
        // places at viewport center must move to (centerX, centerY) px.
        val offsetXNdc = 2f * (centerX - vw / 2f) / vw
        val offsetYNdc = 2f * (centerY - vh / 2f) / vh
        val projection = Mat4.perspectiveWithPrincipalOffset(
            fovyDegrees = profile.fovYDegrees,
            aspect = aspect,
            near = profile.nearMeters,
            far = profile.farMeters,
            principalOffsetXNdc = offsetXNdc,
            principalOffsetYNdc = offsetYNdc,
        )
        return EyeParams(
            viewport = viewport,
            projection = projection,
            camera = camera,
            viewProjection = projection * camera.viewMatrix(),
            principalPoint = PixelPoint(centerX, centerY),
        )
    }

    /**
     * Calibration invariant: the world point straight ahead of THIS eye at
     * any distance projects onto the eye's principal point. Used by host
     * tests and by the on-screen cross-check.
     */
    fun principalPointProjection(eye: EyeParams, distance: Float): PixelPoint {
        val ahead = eye.camera.position + eye.camera.orientation.rotate(
            com.example.ar_glass_plus.render.spatial.Vec3(0f, 0f, -distance),
        )
        return SpatialProjection.projectToPixels(ahead, eye.viewProjection, eye.viewport.width, eye.viewport.height)!!
    }
}
