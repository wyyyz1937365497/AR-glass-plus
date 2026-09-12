package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.api.PointerAction
import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpatialInteractionController behavior tests: hover state, deterministic
 * focus selection, content injection, anchored resize, move/rotate chrome
 * manipulation, and cancellation.
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
        var focusedWindowId = 1L
        val controller = SpatialInteractionController(
            emitIntent = { intents += it },
            readPose = { id -> windows.firstOrNull { it.id == id }?.pose },
            readFocusedWindowId = { focusedWindowId },
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

        fun lastPoseMutation(windowId: Long) =
            intents.filterIsInstance<SpatialIntent.UpdatePose>()
                .lastOrNull { it.windowId == windowId }
                ?.pose
    }

    private fun px(worldX: Float, worldY: Float, z: Float = -1.5f): Pair<Float, Float> {
        val p = com.example.ar_glass_plus.render.spatial.SpatialProjection
            .projectToPixels(Vec3(worldX, worldY, z), vp, fbW, fbH)!!
        return p.x to p.y
    }

    @Test
    fun hoverOverContentUpdatesPointerState() {
        val h = Harness()
        val (x, y) = px(0f, 0f)
        h.controller.onPointerMove(x, y)
        assertEquals(1L, h.controller.state.value.hoveredWindowId)
        assertNotNull(h.controller.state.value.localPoint)
    }

    @Test
    fun hoverOutsideWindowsClearsPointerState() {
        val h = Harness()
        val (x, y) = px(0f, 0f)
        h.controller.onPointerMove(x, y)
        val (ox, oy) = px(2.5f, 1.5f)
        h.controller.onPointerMove(ox, oy)
        assertEquals(null, h.controller.state.value.hoveredWindowId)
        assertEquals(null, h.controller.state.value.localPoint)
    }

    @Test
    fun focusedContentDownUpInjectsPointerSequence() {
        val h = Harness()
        val (x, y) = px(0f, 0f)
        h.controller.onPointerMove(x, y)
        h.controller.onPointerDown(x, y)
        h.controller.onPointerUp()

        val injects = h.intents.filterIsInstance<SpatialIntent.InjectContent>()
        assertEquals(2, injects.size) // DOWN + UP
        assertEquals(PointerAction.DOWN, injects[0].action)
        assertEquals(PointerAction.UP, injects[1].action)
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

        val mutation = h.lastPoseMutation(1L)
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

        // Move the bottom-right grab point farther right and down.
        val target = px(win.w / 2f + 0.15f, -win.h / 2f - 0.10f)
        h.controller.onPointerMove(target.first, target.second)
        h.controller.onPointerUp()

        val mutation = h.lastPoseMutation(1L)
        assertNotNull(mutation)
        mutation!!
        assertEquals(0.97f, mutation.widthMeters, 0.02f)
        assertEquals(0.57f, mutation.heightMeters, 0.02f)
        // Content top-left remains fixed while the opposite corner moves.
        assertEquals(-win.w / 2f, mutation.position.x - mutation.widthMeters / 2f, 0.01f)
        assertEquals(win.h / 2f, mutation.position.y + mutation.heightMeters / 2f, 0.01f)
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

        val mutation = h.lastPoseMutation(1L)
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
        h.focusedWindowId = 2L
        val (sx, sy) = px(win2.x, titleY)
        h.controller.onPointerMove(sx, sy)
        h.controller.onPointerDown(sx, sy)
        val (dx, dy) = px(win2.x - 0.2f, titleY)
        h.controller.onPointerMove(dx, dy)
        h.controller.onPointerUp()

        assertNotNull(h.lastPoseMutation(2L))
        assertEquals(null, h.lastPoseMutation(1L))
    }

    @Test
    fun contentDragMovesAndReleasesAtLatestHitPoint() {
        val h = Harness()
        val (startX, startY) = px(0f, 0f)
        val (endX, endY) = px(0.2f, -0.1f)

        h.controller.onPointerDown(startX, startY, MouseButton.LEFT)
        h.controller.onPointerMove(endX, endY)
        h.controller.onPointerUp()

        val injects = h.intents.filterIsInstance<SpatialIntent.InjectContent>()
        assertEquals(
            listOf(PointerAction.DOWN, PointerAction.MOVE, PointerAction.UP),
            injects.map { it.action },
        )
        assertTrue(injects.last().contentX > injects.first().contentX)
        assertTrue(injects.last().contentY > injects.first().contentY)
        assertEquals(injects[1].contentX, injects[2].contentX, 0.001f)
        assertEquals(injects[1].contentY, injects[2].contentY, 0.001f)
    }

    @Test
    fun contentDragOutsideWindowClampsToEdgeAndStillReleases() {
        val h = Harness()
        val (startX, startY) = px(0f, 0f)
        val (outsideX, outsideY) = px(2.5f, -1.5f)

        h.controller.onPointerDown(startX, startY)
        h.controller.onPointerMove(outsideX, outsideY)
        h.controller.onPointerUp()

        val injects = h.intents.filterIsInstance<SpatialIntent.InjectContent>()
        assertEquals(listOf(PointerAction.DOWN, PointerAction.MOVE, PointerAction.UP), injects.map { it.action })
        assertEquals(1279f, injects.last().contentX, 0.01f)
        assertEquals(719f, injects.last().contentY, 0.01f)
    }

    @Test
    fun scrollFocusesBeforeSendingRawDeltas() {
        val h = Harness()
        val (x, y) = px(h.windowById(2).x, 0f)

        h.controller.onScroll(x, y, dx = 12f, dy = -34f)
        assertEquals(SpatialIntent.Focus(2L), h.intents.single())

        h.focusedWindowId = 2L
        h.intents.clear()
        h.controller.onScroll(x, y, dx = 12f, dy = -34f)
        assertEquals(SpatialIntent.ScrollContent(2L, 12f, -34f), h.intents.single())
    }
    @Test
    fun pointerDownOnUnfocusedWindowFocusesBeforeStartingGesture() {
        val h = Harness()
        val (x, y) = px(h.windowById(2).x, 0f)

        h.controller.onPointerDown(x, y)

        assertTrue(h.intents.single() is SpatialIntent.Focus)
        assertEquals(2L, (h.intents.single() as SpatialIntent.Focus).windowId)
        assertTrue(h.intents.none { it is SpatialIntent.InjectContent })
        assertEquals(SpatialPointerMode.HOVER, h.controller.state.value.mode)
    }

    @Test
    fun focusedWindowWinsCoplanarOverlapHit() {
        val h = Harness()
        h.windowById(2).x = 0f
        h.windowById(2).sync()
        h.focusedWindowId = 2L
        val (x, y) = px(0f, 0f)

        h.controller.onClick(x, y)

        val injection = h.intents.filterIsInstance<SpatialIntent.InjectContent>().single()
        assertEquals(2L, injection.windowId)
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

}
