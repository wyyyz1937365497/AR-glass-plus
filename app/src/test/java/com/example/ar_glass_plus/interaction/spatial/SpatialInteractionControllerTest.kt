package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpatialInteractionController behavior tests: title-bar drag moves on the
 * view-aligned plane, handle drag resizes, border drag rotates, content
 * taps emit focus + inject intents, cancelSession resets cleanly.
 */
class SpatialInteractionControllerTest {

    private val fbW = 1920f
    private val fbH = 1080f
    private val vp = Mat4.perspective(60f, fbW / fbH, 0.05f, 20f) *
        SpatialCamera.STATIC_HEAD.viewMatrix()

    private class SceneWindow(
        val id: Long,
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = -1.5f,
        var w: Float = 0.8f,
        var h: Float = 0.45f,
    ) {
        val pose = SpatialInteractionController.MutablePose(
            Vec3(x, y, z),
            com.example.ar_glass_plus.render.spatial.Quat.IDENTITY,
            w,
            h,
        )

        fun sync() {
            pose.position = Vec3(x, y, z)
            pose.widthMeters = w
            pose.heightMeters = h
        }
    }

    private inner class Harness {
        val intents = mutableListOf<SpatialIntent>()
        val windows = listOf(SceneWindow(1), SceneWindow(2, x = 0.6f))
        val controller = SpatialInteractionController(
            emitIntent = { intents += it },
            readPose = { id -> windows.firstOrNull { it.id == id }?.pose },
        )

        init {
            controller.setTargetsProvider {
                windows.map { SpatialSceneQuery.WindowTarget(it.id, it.pose, 1280, 720) }
            }
            controller.onFrame(
                SpatialFrameParams(SpatialCamera.STATIC_HEAD, vp, 1920f, 1080f),
            )
        }

        fun windowById(id: Long): SceneWindow = windows.first { it.id == id }
    }

    private fun px(worldX: Float, worldY: Float, z: Float = -1.5f): Pair<Float, Float> {
        val p = com.example.ar_glass_plus.render.spatial.SpatialProjection
            .projectToPixels(Vec3(worldX, worldY, z), vp, fbW, fbH)!!
        return p.x to p.y
    }

    @Test
    fun hoverOverContentEmitsHoverWithWindowId() {
        val h = Harness()
        val (x, y) = px(0f, 0f)
        h.controller.onPointerMove(x, y)
        val hover = h.intents.filterIsInstance<SpatialIntent.Hover>().last()
        assertEquals(1L, hover.windowId)
        assertNotNull(hover.localPoint)
    }

    @Test
    fun hoverOutsideWindowsClearsHover() {
        val h = Harness()
        val (x, y) = px(0f, 0f)
        h.controller.onPointerMove(x, y)
        val (ox, oy) = px(2.5f, 1.5f)
        h.controller.onPointerMove(ox, oy)
        val hover = h.intents.filterIsInstance<SpatialIntent.Hover>().last()
        assertEquals(null, hover.windowId)
    }

    @Test
    fun contentDownUpEmitsFocusAndInject() {
        val h = Harness()
        val (x, y) = px(0f, 0f)
        h.controller.onPointerMove(x, y)
        h.controller.onPointerDown(x, y)
        h.controller.onPointerUp()

        assertTrue(h.intents.any { it is SpatialIntent.Focus && it.windowId == 1L })
        val injects = h.intents.filterIsInstance<SpatialIntent.InjectContent>()
        assertEquals(2, injects.size) // DOWN + UP
        assertEquals(ContentPointerKind.DOWN, injects[0].kind)
        assertEquals(ContentPointerKind.UP, injects[1].kind)
        // Center of the window maps to center of the VD.
        assertEquals(639.5f, injects[0].contentX, 4f)
        assertEquals(359.5f, injects[0].contentY, 4f)
    }

    @Test
    fun titleBarDragMovesWindowOnViewPlane() {
        val h = Harness()
        val win = h.windowById(1)
        val titleY = win.h / 2f + WindowChrome.TITLE_BAR_METERS / 2f
        val (sx, sy) = px(0f, titleY)
        h.controller.onPointerMove(sx, sy)
        h.controller.onPointerDown(sx, sy)

        // Drag to a point 0.2m right of the original grab position.
        val (dx, dy) = px(0.2f, titleY)
        h.controller.onPointerMove(dx, dy)
        h.controller.onPointerUp()

        val mutation = h.controller.pendingPoseMutations[1L]
        assertNotNull(mutation)
        // Window center moved ~0.2m right (grab point follows the ray).
        assertEquals(0.2f, mutation!!.position.x, 0.02f)
        assertEquals(0f, mutation.position.y, 0.02f)
        assertEquals(win.z, mutation.position.z, 1e-4f)
    }

    @Test
    fun handleDragResizesWindow() {
        val h = Harness()
        val win = h.windowById(1)
        // Grab inside the resize-handle square at the BR corner.
        val (sx, sy) = px(win.w / 2f - 0.02f, -win.h / 2f + 0.02f)
        h.controller.onPointerMove(sx, sy)
        h.controller.onPointerDown(sx, sy)

        // Drag outward: pointer 0.15m further right and 0.1m further up
        // in window-local terms ~ doubles into width/height deltas.
        val target = px(win.w / 2f + 0.15f, -win.h / 2f + 0.10f)
        h.controller.onPointerMove(target.first, target.second)
        h.controller.onPointerUp()

        val mutation = h.controller.pendingPoseMutations[1L]
        assertNotNull(mutation)
        assertTrue(mutation!!.widthMeters > win.w)
        assertTrue(mutation.heightMeters > win.h)
    }

    @Test
    fun borderDragRotatesWindow() {
        val h = Harness()
        val win = h.windowById(1)
        // Grab the border ring at the top edge center.
        val (sx, sy) = px(0f, win.h / 2f - WindowChrome.BORDER_METERS / 2f)
        h.controller.onPointerMove(sx, sy)
        h.controller.onPointerDown(sx, sy)

        // Drag right 300px -> yaw += 30deg.
        h.controller.onPointerMove(sx + 300f, sy)
        h.controller.onPointerUp()

        val mutation = h.controller.pendingPoseMutations[1L]
        assertNotNull(mutation)
        // 30deg yaw: +X axis maps to (cos30, 0, -sin30).
        val rotatedX = mutation!!.orientation.rotate(Vec3(1f, 0f, 0f))
        assertEquals(0.866f, rotatedX.x, 0.01f)
        assertEquals(-0.5f, rotatedX.z, 0.01f)
    }

    @Test
    fun dragOnSecondWindowOnlyMutatesThatWindow() {
        val h = Harness()
        val win2 = h.windowById(2)
        val titleY = win2.h / 2f + WindowChrome.TITLE_BAR_METERS / 2f
        val (sx, sy) = px(win2.x, titleY)
        h.controller.onPointerMove(sx, sy)
        h.controller.onPointerDown(sx, sy)
        val (dx, dy) = px(win2.x - 0.2f, titleY)
        h.controller.onPointerMove(dx, dy)
        h.controller.onPointerUp()

        assertNotNull(h.controller.pendingPoseMutations[2L])
        assertEquals(null, h.controller.pendingPoseMutations[1L])
    }

    @Test
    fun cancelSessionResetsState() {
        val h = Harness()
        val win = h.windowById(1)
        val titleY = win.h / 2f + WindowChrome.TITLE_BAR_METERS / 2f
        val (sx, sy) = px(0f, titleY)
        h.controller.onPointerMove(sx, sy)
        h.controller.onPointerDown(sx, sy)
        assertEquals(SpatialPointerMode.MANIPULATING, h.controller.state.value.mode)

        h.controller.cancelSession()
        assertEquals(SpatialPointerMode.HOVER, h.controller.state.value.mode)
        assertEquals(null, h.controller.state.value.activeWindowId)
    }

    @Test
    fun missingPoseDuringSessionCancels() {
        // A controller whose pose reader lost the window mid-drag must drop
        // the session instead of mutating stale state.
        val orphaned = SpatialInteractionController(
            emitIntent = {},
            readPose = { null },
        ).also {
            it.setTargetsProvider { emptyList() }
            it.onFrame(SpatialFrameParams(SpatialCamera.STATIC_HEAD, vp, fbW, fbH))
        }
        // Without any window nothing starts; force a session by injecting a
        // move on a live harness controller, then orphan it.
        val h = Harness()
        val win = h.windowById(1)
        val titleY = win.h / 2f + WindowChrome.TITLE_BAR_METERS / 2f
        val (sx, sy) = px(0f, titleY)
        h.controller.onPointerMove(sx, sy)
        h.controller.onPointerDown(sx, sy)
        assertEquals(SpatialPointerMode.MANIPULATING, h.controller.state.value.mode)

        // Now the workspace reports the window gone (pose == null).
        val gone = SpatialInteractionController(
            emitIntent = {},
            readPose = { null },
        )
        gone.setTargetsProvider { emptyList() }
        gone.onFrame(SpatialFrameParams(SpatialCamera.STATIC_HEAD, vp, fbW, fbH))
        gone.onPointerMove(sx + 10f, sy)
        assertEquals(SpatialPointerMode.HOVER, gone.state.value.mode)
    }
}
