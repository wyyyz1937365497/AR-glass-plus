package com.example.ar_glass_plus.render.spatial.calibration

import com.example.ar_glass_plus.render.geometry.PixelPoint
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.StereoCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calibration model tests: eye order, per-eye principal-point projection
 * invariant, viewport split, and the calibration scene's sanity (depths
 * ordered, quads finite).
 */
class StereoCalibrationTest {

    @Test
    fun defaultProfileSplitsOutputIntoEqualHalves() {
        val p = StereoCalibrationProfile.defaultFor(1920, 1080)
        assertEquals(0f, p.leftViewport.left, 0f)
        assertEquals(960f, p.leftViewport.right, 0f)
        assertEquals(960f, p.rightViewport.left, 0f)
        assertEquals(1920f, p.rightViewport.right, 0f)
        assertEquals(EyeOrder.LEFT_FIRST, p.eyeOrder)
    }

    @Test
    fun straightAheadPointLandsOnPrincipalPointPerEye() {
        val p = StereoCalibrationProfile.defaultFor(1920, 1080)
        val stereo = StereoCamera(head = SpatialCamera.STATIC_HEAD, ipdMeters = p.ipdMeters)
        for (eye in listOf(StereoCalibration.leftEye(p, stereo), StereoCalibration.rightEye(p, stereo))) {
            // The invariant must hold at several distances.
            for (d in floatArrayOf(0.5f, 1.0f, 2.5f)) {
                val px = StereoCalibration.principalPointProjection(eye, d)
                assertEquals(eye.principalPoint.x, px.x, 0.5f)
                assertEquals(eye.principalPoint.y, px.y, 0.5f)
            }
        }
    }

    @Test
    fun shiftedPrincipalPointMovesProjectionAccordingly() {
        // Left-eye principal point 48px right of viewport center.
        val base = StereoCalibrationProfile.defaultFor(1920, 1080)
        val p = base.copy(projectionCenterXLeft = base.leftViewport.width / 2f + 48f)
        val stereo = StereoCamera(head = SpatialCamera.STATIC_HEAD, ipdMeters = p.ipdMeters)
        val eye = StereoCalibration.leftEye(p, stereo)
        val px = StereoCalibration.principalPointProjection(eye, 1.0f)
        assertEquals(eye.principalPoint.x, px.x, 0.5f)
        assertEquals(eye.principalPoint.x, base.leftViewport.width / 2f + 48f, 0.5f)
        // Baseline (unshifted) center lands at viewport center.
        val baseEye = StereoCalibration.leftEye(base, stereo)
        val basePx = StereoCalibration.principalPointProjection(baseEye, 1.0f)
        assertEquals(base.leftViewport.width / 2f, basePx.x, 0.5f)
    }

    @Test
    fun eyeOrderRightFirstSwapsRenderSlots() {
        val p = StereoCalibrationProfile.defaultFor(1920, 1080).copy(eyeOrder = EyeOrder.RIGHT_FIRST)
        val stereo = StereoCamera(head = SpatialCamera.STATIC_HEAD, ipdMeters = p.ipdMeters)
        val eyes = StereoCalibration.renderEyes(p, stereo)
        // First rendered slot (physical left half) carries the RIGHT eye.
        assertEquals(p.rightViewport, eyes[0].viewport)
        assertEquals(p.leftViewport, eyes[1].viewport)
    }

    @Test
    fun monoEyeCoversWholeOutput() {
        val p = StereoCalibrationProfile.defaultFor(1920, 1080)
        val eye = StereoCalibration.monoEye(p, SpatialCamera.STATIC_HEAD)
        assertEquals(0f, eye.viewport.left, 0f)
        assertEquals(1920f, eye.viewport.right, 0f)
    }

    @Test
    fun calibrationSceneDepthLadderIsOrdered() {
        assertTrue(CalibrationScene.NEAR_Z > CalibrationScene.MID_Z)
        assertTrue(CalibrationScene.MID_Z > CalibrationScene.FAR_Z)
        val scene = CalibrationScene.build()
        // Depth outlines + grid + fusion/aspect targets are all present.
        assertTrue(scene.quads.size >= 50)
        // All quads finite and non-degenerate.
        scene.quads.forEach { q ->
            assertTrue(q.widthMeters > 0f && q.heightMeters > 0f)
            assertTrue(q.position.x.isFinite() && q.position.y.isFinite() && q.position.z.isFinite())
        }
        // Both eyes have overlays (arrows + cross).
        assertTrue(scene.leftOverlay.triangles.isNotEmpty())
        assertTrue(scene.rightOverlay.triangles.isNotEmpty())
    }

    @Test
    fun nearSquareHasLargerDisparityThanFarSquare() {
        // Project the depth-ladder centers per eye; near must shift more.
        val p = StereoCalibrationProfile.defaultFor(1920, 1080)
        val stereo = StereoCamera(head = SpatialCamera.STATIC_HEAD, ipdMeters = p.ipdMeters)
        val left = StereoCalibration.leftEye(p, stereo)
        val right = StereoCalibration.rightEye(p, stereo)

        fun disparity(z: Float): Float {
            val w = com.example.ar_glass_plus.render.spatial.Vec3(0f, 0f, z)
            val l = StereoCalibration.principalPointProjection(left, 1f).let {
                PixelPoint(it.x, it.y)
            }
            // projectToPixels uses viewport-local coords here via eye VP:
            val lp = com.example.ar_glass_plus.render.spatial.SpatialProjection
                .projectToPixels(w, left.viewProjection, left.viewport.width, left.viewport.height)!!
            val rp = com.example.ar_glass_plus.render.spatial.SpatialProjection
                .projectToPixels(w, right.viewProjection, right.viewport.width, right.viewport.height)!!
            assertTrue(l.x >= 0f) // keep l referenced
            return kotlin.math.abs(rp.x - lp.x)
        }

        val dNear = disparity(CalibrationScene.NEAR_Z)
        val dFar = disparity(CalibrationScene.FAR_Z)
        assertTrue("near disparity must exceed far ($dNear vs $dFar)", dNear > dFar * 2f)
    }
}
