package com.example.ar_glass_plus.render.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPU projection reference tests — the host-verifiable half of Gate 2
 * acceptance F (no mirror/flip), F (perspective), and G (stereo disparity
 * follows d = f·IPD/Z). The GL renderer is verified against these same
 * functions (it consumes SpatialProjection/SpatialCamera directly).
 */
class SpatialProjectionTest {

    private val fbW = 1920f
    private val fbH = 1080f
    private val proj = Mat4.perspective(
        SpatialCamera.DEFAULT_FOV_Y_DEGREES,
        fbW / fbH,
        SpatialCamera.DEFAULT_NEAR_METERS,
        SpatialCamera.DEFAULT_FAR_METERS,
    )
    private val viewProj = proj * SpatialCamera.STATIC_HEAD.viewMatrix()

    private fun pose(
        x: Float = 0f,
        y: Float = 0f,
        z: Float = -1.2f,
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
        w: Float = 0.7f,
        h: Float = 0.394f,
    ): TestPose = TestPose(
        Vec3(x, y, z),
        Quat.fromEulerDegrees(yaw, pitch, roll),
        w,
        h,
    )

    private class TestPose(
        override val position: Vec3,
        override val orientation: Quat,
        override val widthMeters: Float,
        override val heightMeters: Float,
    ) : SpatialPoseRef

    @Test
    fun identityMatrixLeavesPointsUnchanged() {
        val p = Mat4.identity().transform(1f, 2f, 3f, 1f)
        assertEquals(1f, p[0], 1e-5f)
        assertEquals(2f, p[1], 1e-5f)
        assertEquals(3f, p[2], 1e-5f)
        assertEquals(1f, p[3], 1e-5f)
    }

    @Test
    fun yawNinetyRotatesPlusXToMinusZ() {
        val q = Quat.fromEulerDegrees(yawDeg = 90f)
        val v = q.rotate(Vec3(1f, 0f, 0f))
        assertEquals(0f, v.x, 1e-5f)
        assertEquals(0f, v.y, 1e-5f)
        assertEquals(-1f, v.z, 1e-5f)
    }

    @Test
    fun viewMatrixOfStaticHeadIsProjectionOnly() {
        // Head at origin with identity orientation → view = identity.
        val v = SpatialCamera.STATIC_HEAD.viewMatrix()
        val p = v.transform(0f, 0f, -2f, 1f)
        assertEquals(0f, p[0], 1e-5f)
        assertEquals(0f, p[1], 1e-5f)
        assertEquals(-2f, p[2], 1e-5f)
    }

    @Test
    fun viewMatrixOfOffsetCameraMovesWorldIntoCameraSpace() {
        val cam = SpatialCamera(position = Vec3(0f, 0f, 5f))
        val p = cam.viewMatrix().transform(0f, 0f, 0f, 1f)
        assertEquals(0f, p[0], 1e-5f)
        assertEquals(0f, p[1], 1e-5f)
        assertEquals(-5f, p[2], 1e-5f)
    }

    @Test
    fun worldCenterInFrontOfCameraProjectsToFramebufferCenter() {
        val px = SpatialProjection.projectToPixels(Vec3(0f, 0f, -2f), viewProj, fbW, fbH)!!
        assertEquals(fbW / 2f, px.x, 0.5f)
        assertEquals(fbH / 2f, px.y, 0.5f)
    }

    // ── Acceptance E: orientation / no mirror ──

    @Test
    fun unrotatedWindowHasNoMirrorOrFlip() {
        val p = pose()
        val c = SpatialProjection.projectWindowCorners(p, viewProj, fbW, fbH)!!
        val (tl, tr, br, bl) = c
        assertTrue("TL must be left of TR", tl.x < tr.x)
        assertTrue("TL must be above BL (screen y down)", tl.y < bl.y)
        assertTrue("BR must be right of BL", br.x > bl.x)
        assertTrue("TR must be above BR", tr.y < br.y)
        // Horizontal edges level, vertical edges plumb.
        assertEquals(tl.y, tr.y, 0.5f)
        assertEquals(tl.x, bl.x, 0.5f)
    }

    // ── Acceptance F: perspective ──

    @Test
    fun fartherWindowAppearsHalfSize() {
        val near = SpatialProjection.projectWindowCorners(pose(z = -1f, w = 1f, h = 0.5f), viewProj, fbW, fbH)!!
        val far = SpatialProjection.projectWindowCorners(pose(z = -2f, w = 1f, h = 0.5f), viewProj, fbW, fbH)!!
        val nearW = near[1].x - near[0].x
        val farW = far[1].x - far[0].x
        assertEquals(2f, nearW / farW, 0.01f)
    }

    @Test
    fun leftPresetIsLeftOfRightPresetAndHigherPresetIsHigher() {
        val w1 = SpatialProjection.projectWindowCorners(pose(x = -0.55f, y = 0.18f, z = -1.2f), viewProj, fbW, fbH)!!
        val w2 = SpatialProjection.projectWindowCorners(pose(x = 0.50f, y = 0.12f, z = -1.0f), viewProj, fbW, fbH)!!
        val w3 = SpatialProjection.projectWindowCorners(pose(x = -0.30f, y = -0.32f, z = -1.6f), viewProj, fbW, fbH)!!

        val c1 = (w1[0].x + w1[2].x) / 2f
        val c2 = (w2[0].x + w2[2].x) / 2f
        val cy1 = (w1[0].y + w1[2].y) / 2f
        val cy3 = (w3[0].y + w3[2].y) / 2f
        assertTrue("window1 left of window2", c1 < c2)
        assertTrue("higher world Y -> smaller screen Y", cy1 < cy3)
    }

    @Test
    fun positiveYawPushesRightEdgeAwayMakingItSmaller() {
        val straight = SpatialProjection.projectWindowCorners(pose(), viewProj, fbW, fbH)!!
        val yawed = SpatialProjection.projectWindowCorners(pose(yaw = 20f), viewProj, fbW, fbH)!!
        val (tl, tr, br, bl) = yawed
        val rightEdge = kotlin.math.hypot(tr.x - br.x, tr.y - br.y)
        val leftEdge = kotlin.math.hypot(tl.x - bl.x, tl.y - bl.y)
        assertTrue("right edge must project shorter under +yaw", rightEdge < leftEdge)
        // Baseline identity: both edges equal.
        val (sTl, _, sBr, sBl) = straight
        assertEquals(
            kotlin.math.hypot(sTl.x - sBl.x, sTl.y - sBl.y),
            kotlin.math.hypot(straight[1].x - sBr.x, straight[1].y - sBr.y),
            0.5f,
        )
    }

    @Test
    fun positivePitchBringsTopEdgeCloserMakingItLonger() {
        val straight = SpatialProjection.projectWindowCorners(pose(), viewProj, fbW, fbH)!!
        val pitched = SpatialProjection.projectWindowCorners(pose(pitch = 20f), viewProj, fbW, fbH)!!
        val (tl, tr, br, bl) = pitched
        val topEdge = kotlin.math.hypot(tr.x - tl.x, tr.y - tl.y)
        val bottomEdge = kotlin.math.hypot(br.x - bl.x, br.y - bl.y)
        assertTrue("top edge must project longer under +pitch", topEdge > bottomEdge)
        // Baseline identity: both edges equal.
        assertEquals(
            kotlin.math.hypot(straight[1].x - straight[0].x, straight[1].y - straight[0].y),
            kotlin.math.hypot(straight[2].x - straight[3].x, straight[2].y - straight[3].y),
            0.5f,
        )
    }

    @Test
    fun positiveRollTiltsRightEdgeUpOnScreen() {
        val straight = SpatialProjection.projectWindowCorners(pose(), viewProj, fbW, fbH)!!
        val rolled = SpatialProjection.projectWindowCorners(pose(roll = 15f), viewProj, fbW, fbH)!!
        // Identity: TL.y == TR.y. Rolled +: right edge rises -> TR.y < TL.y.
        assertEquals(straight[0].y, straight[1].y, 0.5f)
        assertTrue("TR must rise under +roll", rolled[1].y < rolled[0].y)
    }

    // ── Acceptance D: size independence ──

    @Test
    fun doublingWidthDoublesProjectedWidthAtSameDepth() {
        val small = SpatialProjection.projectWindowCorners(pose(w = 0.7f), viewProj, fbW, fbH)!!
        val big = SpatialProjection.projectWindowCorners(pose(w = 1.4f), viewProj, fbW, fbH)!!
        assertEquals(2f, (big[1].x - big[0].x) / (small[1].x - small[0].x), 0.01f)
    }

    // ── Acceptance G: stereo disparity ──

    @Test
    fun parallelStereoDisparityFollowsInverseDepthLaw() {
        val stereo = StereoCamera()
        val vpL = proj * stereo.leftEye.viewMatrix()
        val vpR = stereo.rightEye.viewMatrix().let { proj * it }

        fun disparityAt(z: Float): Float {
            val world = Vec3(0f, 0f, z)
            val l = SpatialProjection.projectToPixels(world, vpL, fbW, fbH)!!
            val r = SpatialProjection.projectToPixels(world, vpR, fbW, fbH)!!
            return kotlin.math.abs(r.x - l.x)
        }

        val dNear = disparityAt(-0.8f)
        val dFar = disparityAt(-1.6f)
        assertTrue("near disparity must exceed far", dNear > dFar)
        // Pinhole parallel rig: d ∝ 1/Z exactly -> halving Z doubles d.
        assertEquals(2f, dNear / dFar, 0.01f)
    }

    @Test
    fun stereoEyesOffsetAlongHeadRightAxis() {
        val stereo = StereoCamera()
        assertEquals(-stereo.ipdMeters / 2f, stereo.leftEye.position.x, 1e-6f)
        assertEquals(stereo.ipdMeters / 2f, stereo.rightEye.position.x, 1e-6f)
        assertEquals(0f, stereo.leftEye.position.y, 1e-6f)
    }

    // ── Behind camera / cursor mapping ──

    @Test
    fun windowBehindCameraProjectsToNull() {
        assertNull(SpatialProjection.projectWindowCorners(pose(z = +1f), viewProj, fbW, fbH))
    }

    @Test
    fun contentCenterMapsToWindowCenterAndOriginToTopLeft() {
        val p = pose(w = 1.4f, h = 0.788f)
        val c = SpatialProjection.projectWindowCorners(p, viewProj, fbW, fbH)!!
        val center = SpatialProjection.contentPointToOutputPixels(
            640f, 360f, 1280f, 720f, p, viewProj, fbW, fbH,
        )!!
        val tl = SpatialProjection.contentPointToOutputPixels(
            0f, 0f, 1280f, 720f, p, viewProj, fbW, fbH,
        )!!
        assertEquals((c[0].x + c[2].x) / 2f, center.x, 0.5f)
        assertEquals((c[0].y + c[2].y) / 2f, center.y, 0.5f)
        assertEquals(c[0].x, tl.x, 0.5f)
        assertEquals(c[0].y, tl.y, 0.5f)
    }

    @Test
    fun quaternionMultiplyFollowsHamiltonProduct() {
        val a = Quat.fromEulerDegrees(yawDeg = 90f)
        val ab = a * a // 180° about +Y
        val v = ab.rotate(Vec3(1f, 0f, 0f))
        // +X under 180° yaw lands on -X (via -Z at the halfway point).
        assertEquals(-1f, v.x, 1e-5f)
        assertEquals(0f, v.y, 1e-5f)
        assertEquals(0f, v.z, 1e-5f)
    }
}
