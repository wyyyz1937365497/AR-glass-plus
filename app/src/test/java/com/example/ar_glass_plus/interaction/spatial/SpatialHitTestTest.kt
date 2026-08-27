package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPU hit-test matrix — the picking half of Gate 3. Every case follows the
 * acceptance list: identity/rotated/translated/scaled windows, overlap
 * ordering, misses, edge/corner hits, and the full ray→content-pixel chain.
 */
class SpatialHitTestTest {

    private val fbW = 1920f
    private val fbH = 1080f
    private val proj = Mat4.perspective(60f, fbW / fbH, 0.05f, 20f)
    private val vp = proj * SpatialCamera.STATIC_HEAD.viewMatrix()

    private class TestPose(
        override var position: Vec3,
        override var orientation: Quat,
        override var widthMeters: Float,
        override var heightMeters: Float,
    ) : com.example.ar_glass_plus.render.spatial.SpatialPoseRef {
        constructor(
            x: Float = 0f,
            y: Float = 0f,
            z: Float = -1.5f,
            yaw: Float = 0f,
            pitch: Float = 0f,
            roll: Float = 0f,
            w: Float = 0.8f,
            h: Float = 0.45f,
        ) : this(Vec3(x, y, z), Quat.fromEulerDegrees(yaw, pitch, roll), w, h)
    }

    // ── unproject round trip ──

    @Test
    fun centerPixelUnprojectsToForwardRay() {
        val ray = SpatialHitTest.rayFromPixelWithProjection(fbW / 2f, fbH / 2f, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        assertEquals(0f, ray.origin.x, 1e-4f)
        assertEquals(0f, ray.origin.y, 1e-4f)
        assertEquals(0f, ray.direction.x, 1e-4f)
        assertEquals(0f, ray.direction.y, 1e-4f)
        assertTrue(ray.direction.z < 0f)
    }

    @Test
    fun rayMatchesDirectProjection() {
        // A world point and its projected pixel must invert into each other.
        val world = Vec3(0.2f, -0.1f, -1.5f)
        val px = com.example.ar_glass_plus.render.spatial.SpatialProjection
            .projectToPixels(world, vp, fbW, fbH)!!
        val ray = SpatialHitTest.rayFromPixelWithProjection(px.x, px.y, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        // The world point lies on the ray.
        val toWorld = world - ray.origin
        val t = toWorld.dot(ray.direction)
        val closest = ray.pointAt(t)
        assertEquals(world.x, closest.x, 1e-3f)
        assertEquals(world.y, closest.y, 1e-3f)
        assertEquals(world.z, closest.z, 1e-3f)
    }

    // ── plane intersection ──

    @Test
    fun identityWindowCenterHitIsLocalOrigin() {
        val pose = TestPose()
        val ray = SpatialHitTest.rayFromPixelWithProjection(fbW / 2f, fbH / 2f, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        val hit = SpatialHitTest.intersectWindowPlane(ray, pose)!!
        assertEquals(0f, hit.localPoint.x, 1e-4f)
        assertEquals(0f, hit.localPoint.y, 1e-4f)
        // Ray origin sits on the near plane (z=-0.05): distance from the
        // RAY ORIGIN to the window is 1.45, i.e. 1.5 - 0.05.
        assertEquals(1.45f, hit.t, 1e-3f)
        assertEquals(-1.5f, hit.worldPoint.z, 1e-3f)
    }
    @Test
    fun yawedWindowStillHitsAtCenter() {
        val pose = TestPose(yaw = 30f)
        val ray = SpatialHitTest.rayFromPixelWithProjection(fbW / 2f, fbH / 2f, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        val hit = SpatialHitTest.intersectWindowPlane(ray, pose)!!
        assertEquals(0f, hit.localPoint.x, 1e-4f)
        assertEquals(0f, hit.localPoint.y, 1e-4f)
    }

    @Test
    fun pitchedAndRolledWindowsHitAtCenter() {
        for (angles in listOf(Quat.fromEulerDegrees(pitchDeg = 20f), Quat.fromEulerDegrees(rollDeg = 15f))) {
            val pose = TestPose().apply { orientation = angles }
            val ray = SpatialHitTest.rayFromPixelWithProjection(fbW / 2f, fbH / 2f, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
            val hit = SpatialHitTest.intersectWindowPlane(ray, pose)!!
            assertEquals(0f, hit.localPoint.x, 1e-4f)
            assertEquals(0f, hit.localPoint.y, 1e-4f)
        }
    }

    @Test
    fun translatedWindowPixelMapsToTranslatedLocalX() {
        // Window shifted +X: its center appears right of screen center; the
        // local hit at the screen center must be NEGATIVE local x.
        val pose = TestPose(x = 0.3f)
        val ray = SpatialHitTest.rayFromPixelWithProjection(fbW / 2f, fbH / 2f, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        val hit = SpatialHitTest.intersectWindowPlane(ray, pose)!!
        assertTrue(hit.localPoint.x < 0f)
        // And the window's own center pixel inverts back to local origin.
        val centerPx = com.example.ar_glass_plus.render.spatial.SpatialProjection
            .projectToPixels(pose.position, vp, fbW, fbH)!!
        val ray2 = SpatialHitTest.rayFromPixelWithProjection(centerPx.x, centerPx.y, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        val hit2 = SpatialHitTest.intersectWindowPlane(ray2, pose)!!
        assertEquals(0f, hit2.localPoint.x, 1e-3f)
        assertEquals(0f, hit2.localPoint.y, 1e-3f)
    }

    // ── UV / content mapping ──

    @Test
    fun localToUvCoversUnitSquareAndRejectsOutside() {
        val pose = TestPose(w = 1f, h = 0.5f)
        assertEquals(0.5f, SpatialHitTest.localToUv(Vec3(0f, 0f, 0f), pose)!!.first, 1e-5f)
        assertEquals(0f, SpatialHitTest.localToUv(Vec3(-0.5f, 0f, 0f), pose)!!.first, 1e-5f)
        assertEquals(1f, SpatialHitTest.localToUv(Vec3(0.5f, 0f, 0f), pose)!!.first, 1e-5f)
        // +Y up -> v = 0 at top.
        assertEquals(0f, SpatialHitTest.localToUv(Vec3(0f, 0.25f, 0f), pose)!!.second, 1e-5f)
        assertNull(SpatialHitTest.localToUv(Vec3(0.51f, 0f, 0f), pose))
        assertNull(SpatialHitTest.localToUv(Vec3(0f, 0.26f, 0f), pose))
    }

    @Test
    fun uvToContentPixelsMapsCorners() {
        assertEquals(0f to 0f, SpatialHitTest.uvToContentPixels(0f, 0f, 1280, 720))
        assertEquals(1279f to 719f, SpatialHitTest.uvToContentPixels(1f, 1f, 1280, 720))
        assertEquals(639.5f to 359.5f, SpatialHitTest.uvToContentPixels(0.5f, 0.5f, 1280, 720))
    }

    @Test
    fun rayToContentPixelsTopLeftIsContentTopLeft() {
        val pose = TestPose()
        // Interior point 1cm inside the local TL corner, so pixel
        // quantization cannot push it out of the quad.
        val inset = 0.01f
        val tlWorld = pose.position + pose.orientation.rotate(
            Vec3(-pose.widthMeters / 2f + inset, pose.heightMeters / 2f - inset, 0f),
        )
        val px = com.example.ar_glass_plus.render.spatial.SpatialProjection.projectToPixels(tlWorld, vp, fbW, fbH)!!
        val ray = SpatialHitTest.rayFromPixelWithProjection(px.x, px.y, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        val (cx, cy) = SpatialHitTest.rayToContentPixels(ray, pose, 1280, 720)!!
        // 1cm into an 0.8m-wide window = 1.25% of 1280 = 16px.
        assertEquals(16f, cx, 3f)
        assertEquals(16f, cy, 3f)
    }

    // ── scene query / ordering / chrome ──

    @Test
    fun overlappingWindowsResolveToNearestFront() {
        val near = SpatialSceneQuery.WindowTarget(1, TestPose(z = -1.0f), 1280, 720)
        val far = SpatialSceneQuery.WindowTarget(2, TestPose(z = -2.0f), 1280, 720)
        val ray = SpatialHitTest.rayFromPixelWithProjection(fbW / 2f, fbH / 2f, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        val hit = SpatialSceneQuery.hitTest(ray, listOf(far, near))!!
        assertEquals(1L, hit.windowId)
        assertEquals(0.95f, hit.distance, 1e-3f) // 1.0 window - 0.05 near origin
    }

    @Test
    fun rayMissReturnsNull() {
        val pose = TestPose(x = 2.5f) // far off to the side
        val ray = SpatialHitTest.rayFromPixelWithProjection(fbW / 2f, fbH / 2f, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
        assertNull(SpatialSceneQuery.hitTest(ray, listOf(SpatialSceneQuery.WindowTarget(1, pose, 1280, 720))))
    }

    @Test
    fun parallelRayMisses() {
        // A yaw-90 window has normal (+/-1,0,0): a forward (0,0,-1) ray IS
        // parallel to it and must miss; an edge-on ray along +X hits.
        val pose = TestPose(yaw = 90f)
 val forward = Ray3(Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f))
        val alongPlane = Ray3(Vec3(0f, 0f, -1.5f), Vec3(0f, 1f, 0f))
        assertNull(SpatialHitTest.intersectWindowPlane(forward, pose))
        assertNull(SpatialHitTest.intersectWindowPlane(alongPlane, pose))
        val crossing = Ray3(Vec3(0f, 0f, -1.5f), Vec3(1f, 0f, 0f))
        val hit = SpatialHitTest.intersectWindowPlane(crossing, pose)
        assertNotNull(hit)
        assertEquals(0f, hit!!.localPoint.x, 1e-4f)
        assertEquals(0f, hit.localPoint.y, 1e-4f)
    }

    @Test
    fun behindCameraReturnsNull() {
        val pose = TestPose()
        val backward = Ray3(Vec3(0f, 0f, 1f), Vec3(0f, 0f, 1f)) // pointing away
        assertNull(SpatialHitTest.intersectWindowPlane(backward, pose))
    }

    @Test
    fun chromeRegionsClassify() {
        val pose = TestPose(w = 0.8f, h = 0.45f)
        assertEquals(WindowChrome.Region.CONTENT, WindowChrome.regionAt(Vec3(0f, 0f, 0f), pose))
        assertEquals(WindowChrome.Region.TITLE_BAR, WindowChrome.regionAt(Vec3(0f, 0.24f, 0f), pose))
        assertEquals(WindowChrome.Region.RESIZE_HANDLE, WindowChrome.regionAt(Vec3(0.39f, -0.22f, 0f), pose))
        assertEquals(WindowChrome.Region.BORDER, WindowChrome.regionAt(Vec3(0f, 0.221f, 0f), pose))
        assertEquals(WindowChrome.Region.OUTSIDE, WindowChrome.regionAt(Vec3(0f, 0.30f, 0f), pose))
        assertEquals(WindowChrome.Region.OUTSIDE, WindowChrome.regionAt(Vec3(0.9f, 0f, 0f), pose))
    }

    @Test
    fun scaledWindowChromeScalesWithContent() {
        val small = TestPose(w = 0.3f, h = 0.2f)
        assertEquals(WindowChrome.Region.CONTENT, WindowChrome.regionAt(Vec3(0f, 0f, 0f), small))
        // Title bar is a fixed band ABOVE content — same offset either size.
        assertEquals(WindowChrome.Region.TITLE_BAR, WindowChrome.regionAt(Vec3(0f, 0.105f, 0f), small))
        // Handle square anchors to the (smaller) BR corner.
        assertEquals(WindowChrome.Region.RESIZE_HANDLE, WindowChrome.regionAt(Vec3(0.14f, -0.09f, 0f), small))
        assertEquals(WindowChrome.Region.OUTSIDE, WindowChrome.regionAt(Vec3(0.16f, -0.09f, 0f), small))
    }

    @Test
    fun edgeAndCornerHitsResolve() {
        val pose = TestPose(w = 1f, h = 0.5f)
        val targets = listOf(SpatialSceneQuery.WindowTarget(7, pose, 1280, 720))

        // Interior points near each corner must hit and classify sanely.
        for ((lx, ly) in listOf(-0.49f to 0.24f, 0.49f to 0.24f, -0.49f to -0.24f, 0.47f to -0.22f)) {
            val world = pose.position + pose.orientation.rotate(Vec3(lx, ly, 0f))
            val px = com.example.ar_glass_plus.render.spatial.SpatialProjection.projectToPixels(world, vp, fbW, fbH)!!
            val ray = SpatialHitTest.rayFromPixelWithProjection(px.x, px.y, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
            val hit = SpatialSceneQuery.hitTest(ray, targets)
            assertNotNull("interior point ($lx, $ly) must hit", hit)
            if (lx == 0.47f && ly == -0.22f) {
                // Inside the resize-handle square at the BR corner.
                assertTrue(hit is SpatialHit.ResizeHandle)
            }
        }

        // A point clearly OUTSIDE every corner misses entirely.
        val outsideWorld = pose.position + Vec3(0.9f, 0.4f, 0f)
        val opx = com.example.ar_glass_plus.render.spatial.SpatialProjection.projectToPixels(outsideWorld, vp, fbW, fbH)
        if (opx != null) {
            val ray = SpatialHitTest.rayFromPixelWithProjection(opx.x, opx.y, fbW, fbH, SpatialCamera.STATIC_HEAD, vp)!!
            assertNull(SpatialSceneQuery.hitTest(ray, targets))
        }
    }

    @Test
    fun inverseOfViewProjectionRoundTrips() {
        val inv = Mat4.inverse(vp)!!
        val v = Vec3(0.3f, -0.2f, -2f)
        val clip = vp.transform(v.x, v.y, v.z, 1f)
        val back = inv.transform(clip[0], clip[1], clip[2], clip[3])
        assertEquals(v.x, back[0] / back[3], 1e-4f)
        assertEquals(v.y, back[1] / back[3], 1e-4f)
        assertEquals(v.z, back[2] / back[3], 1e-4f)
    }

    @Test
    fun singularMatrixInverseIsNull() {
        assertNull(Mat4.inverse(Mat4.scale(1f, 1f, 0f)))
    }

    @Test
    fun principalOffsetProjectionShiftsCenterPredictably() {
        val base = Mat4.perspective(60f, 16f / 9f, 0.05f, 20f)
        val shifted = Mat4.perspectiveWithPrincipalOffset(60f, 16f / 9f, 0.05f, 20f, 0.2f, 0f)
        val p = Vec3(0f, 0f, -1f)
        val b = com.example.ar_glass_plus.render.spatial.SpatialProjection.projectToPixels(p, base * SpatialCamera.STATIC_HEAD.viewMatrix(), 1920f, 1080f)!!
        val s = com.example.ar_glass_plus.render.spatial.SpatialProjection.projectToPixels(p, shifted * SpatialCamera.STATIC_HEAD.viewMatrix(), 1920f, 1080f)!!
        // Positive principal offset moves straight-ahead content RIGHT
        // (a principal point right of center must receive center rays).
        assertEquals(b.x + 0.1f * 1920f, s.x, 1f)
        assertEquals(b.y, s.y, 1f)
    }
}
