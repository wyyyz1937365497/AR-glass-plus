package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.CursorState
import com.example.ar_glass_plus.input.api.InputBackend
import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.api.PointerAction
import com.example.ar_glass_plus.input.touchpad.TrackpadGesture
import com.example.ar_glass_plus.interaction.spatial.SpatialFrameParams
import com.example.ar_glass_plus.interaction.spatial.SpatialInteractionController
import com.example.ar_glass_plus.interaction.spatial.SpatialIntent
import com.example.ar_glass_plus.interaction.spatial.SpatialSceneQuery
import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.input.mouse.MouseController
import com.example.ar_glass_plus.input.touchpad.TrackpadConfig
import com.example.ar_glass_plus.input.touchpad.TrackpadGestureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSessionTest {

    @Test
    fun contentReadyNotifiesInputSessionExactlyOnceWithDisplayAndSize() = runBlocking {
        // Given
        val input = FakeInputSession()
        val controller = controller(input)
        controller.onOutputConnected(7)
        controller.startWorkspace()
        val id = controller.openApp(APP).let { (it as OpenAppResult.Opened).windowId }

        // When
        controller.onWindowContentReady(id, contentDisplayId = 11, width = 1280, height = 720, densityDpi = 240)

        // Then
        assertEquals(listOf(ReadyCall(11, ContentSize(1280, 720))), input.readyCalls)
    }

    @Test
    fun contentGoneNotifiesInputSession() = runBlocking {
        // Given
        val input = FakeInputSession()
        val controller = controller(input)
        controller.onOutputConnected(7)
        controller.startWorkspace()
        val id = controller.openApp(APP).let { (it as OpenAppResult.Opened).windowId }
        controller.onWindowContentReady(id, 11, 1280, 720, 240)

        // When
        controller.onWindowContentGone(id, 11)

        // Then
        assertEquals(1, input.goneCount)
    }

    @Test
    fun workspaceStopNotifiesInputSessionThatContentIsGone() = runBlocking {
        // Given
        val input = FakeInputSession()
        val controller = controller(input)
        controller.onOutputConnected(7)
        controller.startWorkspace()
        val id = controller.openApp(APP).let { (it as OpenAppResult.Opened).windowId }
        controller.onWindowContentReady(id, 11, 1280, 720, 240)

        // When
        controller.stopWorkspace()

        // Then
        assertEquals(1, input.goneCount)
    }

    @Test
    fun nullCursorClearsContentReadyCursorState() = runBlocking {
        // Given
        val controller = controller(FakeInputSession())
        controller.onOutputConnected(7)
        controller.startWorkspace()
        val id = controller.openApp(APP).let { (it as OpenAppResult.Opened).windowId }
        controller.onWindowContentReady(id, 11, 1280, 720, 240)
        controller.updateCursor(CursorState(640f, 360f, visible = true))

        // When
        controller.updateCursor(null)

        // Then
        assertNull(controller.state.cursor)
    }

    @Test
    fun retargetReleasesButtonsOnOldDisplayBeforeSettingNewTarget() = runBlocking {
        // Given
        val backend = RecordingInputBackend()
        var cursorState: CursorState? = null
        val cursor = CursorController(
            onContentSize = { 1280 to 720 },
            cursorProvider = { cursorState },
            onCursorChanged = { cursorState = it },
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val mouse = MouseController(
            backend = backend,
            cursor = cursor,
            cursorProvider = { cursorState },
            scope = scope,
        )
        val engine = TrackpadGestureEngine(
            config = TrackpadConfig(8f, 100f, 300L, 500L),
            scope = scope,
        )
        val input = RealInputSession(backend, cursor, mouse, engine)
        input.onContentReady(11, ContentSize(1280, 720))
        backend.calls.clear()

        // When
        input.onContentReady(22, ContentSize(1920, 1080))

        // Then
        assertEquals(listOf("reset:11", "target:22"), backend.calls)
    }

    @Test
    fun spatialDragMovesAbsolutelyBeforeButtonTransitions() = runBlocking {
        val backend = RecordingInputBackend()
        var cursorState: CursorState? = null
        val cursor = CursorController(
            onContentSize = { 1280 to 720 },
            cursorProvider = { cursorState },
            onCursorChanged = { cursorState = it },
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val mouse = MouseController(
            backend = backend,
            cursor = cursor,
            cursorProvider = { cursorState },
            scope = scope,
        )
        val engine = TrackpadGestureEngine(
            config = TrackpadConfig(8f, 100f, 300L, 500L),
            scope = scope,
        )
        val input = RealInputSession(backend, cursor, mouse, engine)
        input.onContentReady(11, ContentSize(1280, 720))
        backend.calls.clear()

        input.onPointer(PointerAction.DOWN, MouseButton.LEFT, 100f, 200f)
        input.onPointer(PointerAction.MOVE, MouseButton.LEFT, 180f, 260f)
        input.onPointer(PointerAction.UP, MouseButton.LEFT, 180f, 260f)

        assertEquals(
            listOf(
                "absolute:100.0,200.0",
                "down:LEFT",
                "absolute:180.0,260.0",
                "absolute:180.0,260.0",
                "up:LEFT",
            ),
            backend.calls,
        )
        assertEquals(CursorState(180f, 260f, visible = true, pressed = false), cursorState)
    }

    @Test
    fun stereoMouseRoutesClickThroughSpatialHitWithoutDirectBackendCall() {
        val intents = mutableListOf<SpatialIntent>()
        val pose = SpatialPose()
        val interaction = SpatialInteractionController(
            emitIntent = { intents += it },
            readPose = { pose },
            readFocusedWindowId = { 1L },
        ).also { controller ->
            controller.setTargetsProvider {
                listOf(SpatialSceneQuery.WindowTarget(1L, pose, 1280, 720))
            }
            val projection = Mat4.perspective(60f, 16f / 9f, 0.05f, 20f) *
                SpatialCamera.STATIC_HEAD.viewMatrix()
            controller.onFrame(
                SpatialFrameParams(
                    SpatialCamera.STATIC_HEAD,
                    projection,
                    1920f,
                    1080f,
                ),
            )
        }
        val backend = RecordingInputBackend()
        val cursor = CursorController(onContentSize = { 1280 to 720 })
        val mouse = MouseController(
            backend = backend,
            cursor = cursor,
            cursorProvider = { null },
            scope = CoroutineScope(Dispatchers.Unconfined),
            spatialInteraction = interaction,
            spatialEnabled = { true },
        )
        mouse.setPadSize(400f, 300f)

        mouse.onGesture(TrackpadGesture.LeftClick)

        val click = intents.filterIsInstance<SpatialIntent.InjectContent>().single()
        assertEquals(PointerAction.CLICK, click.action)
        assertEquals(MouseButton.LEFT, click.button)
        assertTrue(backend.calls.isEmpty())
    }

    private fun controller(input: InputSession): WorkspaceController = WorkspaceController(
        store = WorkspaceStore(),
        host = object : WorkspaceHostPort {
            override fun start(outputDisplayId: Int) = true
            override fun stop() = Unit
        },
        appLauncher = object : WorkspaceAppLauncherPort {
            override suspend fun launch(app: ActiveApp, contentDisplayId: Int) = true
        },
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).also { it.setInputSession(input) }

    private class FakeInputSession : InputSession {
        val readyCalls = mutableListOf<ReadyCall>()
        var goneCount = 0

        override suspend fun onContentReady(contentDisplayId: Int, size: ContentSize) {
            readyCalls += ReadyCall(contentDisplayId, size)
        }


        override suspend fun onPointer(
            action: PointerAction,
            button: MouseButton,
            contentX: Float,
            contentY: Float,
        ) = Unit

        override suspend fun onScroll(dx: Float, dy: Float) = Unit
        override suspend fun onContentGone() {
            goneCount += 1
        }

        override suspend fun dispose() = Unit
    }

    private class RecordingInputBackend : InputBackend {
        val calls = mutableListOf<String>()
        private var targetDisplayId = -1

        override suspend fun setTargetDisplay(displayId: Int, width: Int, height: Int) {
            targetDisplayId = displayId
            calls += "target:$displayId"
        }

        override suspend fun resetInputState() {
            calls += "reset:$targetDisplayId"
        }

        override suspend fun moveRelative(dx: Float, dy: Float) = Unit
        override suspend fun moveAbsolute(x: Float, y: Float) {
            calls += "absolute:$x,$y"
        }
        override suspend fun buttonDown(button: MouseButton) {
            calls += "down:$button"
        }
        override suspend fun buttonUp(button: MouseButton) {
            calls += "up:$button"
        }
        override suspend fun click(button: MouseButton, x: Float, y: Float) = Unit
        override suspend fun scroll(dx: Float, dy: Float) = Unit
        override suspend fun key(keyCode: Int) = Unit
        override suspend fun close() = Unit
    }

    private companion object {
        val APP = ActiveApp("com.example.notes", "com.example.notes.MainActivity", "Notes")
    }

    private data class ReadyCall(
        val contentDisplayId: Int,
        val size: ContentSize,
    )
}
