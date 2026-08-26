package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.CursorState
import com.example.ar_glass_plus.input.api.InputBackend
import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.mouse.MouseController
import com.example.ar_glass_plus.input.touchpad.TrackpadConfig
import com.example.ar_glass_plus.input.touchpad.TrackpadGestureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        override suspend fun buttonDown(button: MouseButton) = Unit
        override suspend fun buttonUp(button: MouseButton) = Unit
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
