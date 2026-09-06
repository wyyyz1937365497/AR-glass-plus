package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.render.spatial.calibration.StereoCalibrationProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationDraftTest {

    @Test
    fun defaultUsesEyeLocalCentersForRealFullSbsOutput() {
        val profile = CalibrationDraft.default().applyTo(3840, 1080)

        assertEquals(960f, profile.projectionCenterXLeft, 0f)
        assertEquals(540f, profile.projectionCenterYLeft, 0f)
        assertEquals(960f, profile.projectionCenterXRight, 0f)
        assertEquals(540f, profile.projectionCenterYRight, 0f)
    }

    @Test
    fun eyeLocalReferenceScalesToAnyOutputSize() {
        val profile = CalibrationDraft.default().applyTo(1920, 1080)

        assertEquals(480f, profile.projectionCenterXLeft, 0f)
        assertEquals(480f, profile.projectionCenterXRight, 0f)
        assertEquals(540f, profile.projectionCenterYLeft, 0f)
        assertEquals(540f, profile.projectionCenterYRight, 0f)
    }

    @Test
    fun normalizedClampsPersistentAndDirectInputValues() {
        val value = CalibrationDraft(
            ipdMeters = 1f,
            leftCenterX = -10f,
            leftCenterY = 5000f,
            rightCenterX = 5000f,
            rightCenterY = -10f,
            fovYDegrees = 180f,
        ).normalized()

        assertEquals(CalibrationDraft.MAX_IPD_METERS, value.ipdMeters, 0f)
        assertEquals(0f, value.leftCenterX, 0f)
        assertEquals(CalibrationDraft.REFERENCE_EYE_HEIGHT_PX, value.leftCenterY, 0f)
        assertEquals(CalibrationDraft.REFERENCE_EYE_WIDTH_PX, value.rightCenterX, 0f)
        assertEquals(0f, value.rightCenterY, 0f)
        assertEquals(CalibrationDraft.MAX_FOV_Y_DEGREES, value.fovYDegrees, 0f)
    }

    @Test
    fun fovIsForwardedToStereoProfile() {
        val profile = CalibrationDraft.default()
            .copy(fovYDegrees = 72.5f)
            .applyTo(3840, 1080)

        assertEquals(72.5f, profile.fovYDegrees, 0f)
        assertEquals(StereoCalibrationProfile.DEFAULT_IPD_METERS, profile.ipdMeters, 0f)
    }
}
