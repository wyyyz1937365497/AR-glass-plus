package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorState
import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.api.PointerAction
import com.example.ar_glass_plus.interaction.spatial.SpatialIntent
import com.example.ar_glass_plus.render.geometry.AspectMode
import com.example.ar_glass_plus.render.geometry.ContentRotation
import com.example.ar_glass_plus.render.geometry.RenderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class WorkspaceControllerTest {

    @Test
    fun outputDiscoveryReachesOutputReadyWithoutStartingWorkspaceResources() {
        // Given
        val fixture = Fixture()

        // When
        fixture.controller.onOutputConnected(7)

        // Then
        assertEquals(WorkspaceStatus.OUTPUT_READY, fixture.controller.state.status)
        assertEquals(7, fixture.controller.state.outputDisplayId)
        assertEquals(0, fixture.host.startCount)

        // When
        fixture.controller.startWorkspace()

        // Then
        assertEquals(1, fixture.host.startCount)
        assertEquals(7, fixture.host.startedOutputDisplayIds.single())
    }

    @Test
    fun hostStartFailureKeepsOpenAppRejectedAndAllowsSuccessfulRetry() = runBlocking {
        // Given
        val fixture = Fixture(hostStartSucceeds = false)
        fixture.controller.onOutputConnected(7)
        val app = ActiveApp("com.example.reader", "com.example.reader.MainActivity", "Reader")

        // When
        val firstStart = fixture.controller.startWorkspace()
        val openAfterFailedStart = fixture.controller.openApp(app)

        // Then
        assertFalse(firstStart)
        assertEquals(OpenAppResult.Rejected(OpenAppResult.Reason.NOT_RUNNING), openAfterFailedStart)
        assertEquals(WorkspaceStatus.OUTPUT_READY, fixture.controller.state.status)
        assertTrue(fixture.controller.state.windows.isEmpty())

        // Given
        fixture.host.startSucceeds = true

        // When
        val retryStart = fixture.controller.startWorkspace()
        val openAfterRetry = fixture.controller.openApp(app)

        // Then
        assertTrue(retryStart)
        assertTrue(openAfterRetry is OpenAppResult.Opened)
        assertEquals(2, fixture.host.startCount)
        assertEquals(WorkspaceStatus.CONTENT_READY, fixture.controller.state.status)
    }

    @Test
    fun openAppBeforeOutputConnectedIsRejected() = runBlocking {
        // Given
        val fixture = Fixture()

        // When
        val result = fixture.controller.openApp(ActiveApp("a", "a.Main", "A"))

        // Then
        assertEquals(OpenAppResult.Rejected(OpenAppResult.Reason.NOT_RUNNING), result)
        assertTrue(fixture.controller.state.windows.isEmpty())
    }

    @Test
    fun rootAvailabilityProbeUsesControllerPortAndStoresResult() = runBlocking {
        // Given
        val fixture = Fixture(rootAvailable = true)

        // When
        fixture.controller.probeRootAvailability()

        // Then
        assertEquals(1, fixture.root.probeCount)
        assertTrue(fixture.controller.state.rootAvailable)
    }

    @Test
    fun openAppCreatesCreatingWindowAtPresetPoseAndFocusesIt() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()

        // When
        val result = fixture.controller.openApp(APP_NOTES)

        // Then
        val id = (result as OpenAppResult.Opened).windowId
        val window = fixture.controller.state.windows.single()
        assertEquals(id, window.id)
        assertEquals(WindowLifecycle.CREATING, window.lifecycle)
        assertEquals(SpatialWindowModel.presetPose(0), window.pose)
        assertEquals(APP_NOTES, window.app)
        assertNull(window.contentDisplayId)
        assertNull(window.content)
        assertEquals(id, fixture.controller.state.focusedWindowId)
        // Nothing launched yet — the app starts only after content is ready.
        assertTrue(fixture.launcher.requests.isEmpty())
    }

    @Test
    fun windowContentReadyRunsInputRetargetThenLaunchAndMarksRunning() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        val id = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)

        // Then: window RUNNING, content recorded, cleanup owned.
        val window = fixture.controller.state.windows.single()
        assertEquals(WindowLifecycle.RUNNING, window.lifecycle)
        assertEquals(11, window.contentDisplayId)
        assertEquals(ContentSpec(1280, 720, 240), window.content)
        assertEquals(11, fixture.launcher.requests.single().contentDisplayId)
        assertEquals(APP_NOTES, fixture.launcher.requests.single().app)
        assertEquals(WorkspaceStatus.RUNNING, fixture.controller.state.status)
        // Retarget went through the standard input path exactly once.
        assertEquals(listOf(ReadyCall(11, ContentSize(1280, 720))), fixture.input.readyCalls)
        // The controller state stays consistent with the input callback
        // (Unconfined scope: callback already ran).
        assertEquals(11, fixture.controller.state.contentDisplayId)
    }

    @Test
    fun windowContentReadyForNonFocusedWindowDoesNotTouchInput() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val w2id = (fixture.controller.openApp(APP_MAPS) as OpenAppResult.Opened).windowId
        // Focus back to w1 so w2's content-ready is a background event.
        assertTrue(fixture.controller.focusWindow(w1))
        fixture.input.readyCalls.clear()

        // When
        fixture.controller.onWindowContentReady(w2id, 12, 1280, 720, 240)

        // Then
        assertEquals(WindowLifecycle.RUNNING, fixture.window(w2id).lifecycle)
        assertEquals(12, fixture.window(w2id).contentDisplayId)
        assertTrue(fixture.input.readyCalls.isEmpty())
        assertEquals(0, fixture.input.goneCount)
        // Focus untouched.
        assertEquals(w1, fixture.controller.state.focusedWindowId)
    }

    @Test
    fun failedLaunchRemovesWindowWithoutForceStop() = runBlocking {
        // Given
        val fixture = Fixture(launchSucceeds = false)
        fixture.controller.onOutputConnected(9)
        fixture.controller.startWorkspace()
        val id = (fixture.controller.openApp(APP_NOTES) as OpenAppResult.Opened).windowId

        // When
        fixture.controller.onWindowContentReady(id, 15, 1280, 720, 240)

        // Then: window auto-closed, no app to stop (am start failed).
        assertTrue(fixture.controller.state.windows.isEmpty())
        assertNull(fixture.controller.state.focusedWindowId)
        assertEquals(WorkspaceStatus.OUTPUT_READY, fixture.controller.state.status)
        assertTrue(fixture.launcher.stoppedApps.isEmpty())
        // The window was focused while dying, so input was released once.
        assertEquals(1, fixture.input.goneCount)
    }

    @Test
    fun samePackageReopenFocusesExistingWindowWithoutNewAttach() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12) // w2 now focused
        fixture.input.readyCalls.clear()
        val attachCountBefore = fixture.launcher.requests.size

        // When
        val result = fixture.controller.openApp(APP_NOTES)

        // Then: no second window, no second launch — retarget to display 11.
        assertEquals(OpenAppResult.FocusedExisting(w1), result)
        assertEquals(2, fixture.controller.state.windows.size)
        assertEquals(attachCountBefore, fixture.launcher.requests.size)
        assertEquals(w1, fixture.controller.state.focusedWindowId)
        assertEquals(listOf(ReadyCall(11, ContentSize(1280, 720))), fixture.input.readyCalls)
    }

    @Test
    fun samePackageReopenWhileCreatingFocusesWithoutRetarget() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        val id = (fixture.controller.openApp(APP_NOTES) as OpenAppResult.Opened).windowId
        fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        fixture.input.readyCalls.clear()

        // When
        val result = fixture.controller.openApp(APP_NOTES)

        // Then: CREATING window has no content yet — focus only.
        assertEquals(OpenAppResult.FocusedExisting(id), result)
        assertEquals(id, fixture.controller.state.focusedWindowId)
        assertTrue(fixture.input.readyCalls.isEmpty())
    }

    @Test
    fun fourthWindowGetsFourthPresetPoseAndFifthIsRejected() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        fixture.openRunningWindow(APP_BROWSER, contentDisplayId = 13)
        val w4 = fixture.openRunningWindow(APP_VIDEO, contentDisplayId = 14)
        assertEquals(
            SpatialWindowModel.PRESET_POSES,
            fixture.controller.state.windows.map { it.pose },
        )

        // When
        val fifth = fixture.controller.openApp(ActiveApp("com.example.five", "com.example.five.Main", "Five"))

        // Then
        assertEquals(OpenAppResult.Rejected(OpenAppResult.Reason.NO_FREE_SLOT), fifth)
        assertEquals(4, fixture.controller.state.windows.size)
        assertEquals(w4, fixture.controller.state.focusedWindowId)
    }

    @Test
    fun newWindowAfterCloseKeepsOthersPosesAndGetsPoseByCount() = runBlocking {
        // Given: three windows at presets 0,1,2.
        val fixture = runningWorkspaceFixture()
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val w2 = fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        fixture.openRunningWindow(APP_BROWSER, contentDisplayId = 13)
        val pose1 = fixture.window(w1).pose
        val pose3 = fixture.window(fixture.controller.state.focusedWindowId!!).pose

        // When: close middle window, open a new app.
        assertTrue(fixture.controller.closeWindow(w2))
        val w4 = fixture.controller.openApp(APP_VIDEO)

        // Then: remaining windows never moved; the new window takes the
        // preset for its creation count (2 -> third preset).
        assertEquals(3, fixture.controller.state.windows.size)
        assertEquals(SpatialWindowModel.presetPose(2), fixture.window((w4 as OpenAppResult.Opened).windowId).pose)
        assertEquals(pose1, fixture.window(w1).pose)
        assertEquals(pose3, fixture.window(fixture.controller.state.windows.last { it.id != w1 && it.id != (w4 as OpenAppResult.Opened).windowId }.id).pose)
    }

    @Test
    fun adjustFocusedWindowMovesPoseAndClampsSize() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val before = fixture.controller.state.focusedWindow!!.pose

        // When: translate, rotate, resize.
        fixture.controller.adjustFocusedWindow(dxMeters = 0.05f, dyMeters = -0.05f, dzMeters = 0.1f)
        fixture.controller.adjustFocusedWindow(dyawDeg = 10f, dpitchDeg = -5f, drollDeg = 3f)
        fixture.controller.adjustFocusedWindow(dWidthMeters = 0.2f, dHeightMeters = 0.1f)

        // Then
        val after = fixture.controller.state.focusedWindow!!.pose
        assertEquals(before.position.x + 0.05f, after.position.x, 1e-4f)
        assertEquals(before.position.y - 0.05f, after.position.y, 1e-4f)
        assertEquals(before.position.z + 0.1f, after.position.z, 1e-4f)
        assertEquals(before.widthMeters + 0.2f, after.widthMeters, 1e-4f)
        assertEquals(before.heightMeters + 0.1f, after.heightMeters, 1e-4f)
        assertTrue(after.orientation != before.orientation)

        // Size clamps at a sane floor.
        repeat(50) { fixture.controller.adjustFocusedWindow(dWidthMeters = -0.1f) }
        assertTrue(fixture.controller.state.focusedWindow!!.pose.widthMeters >= 0.05f)

        // No focused window -> no-op (state unchanged).
        fixture.controller.stopWorkspace()
        fixture.controller.adjustFocusedWindow(dxMeters = 1f)
        assertTrue(fixture.controller.state.windows.isEmpty())
    }

    @Test
    fun focusWindowOnReadyWindowRetargetsInputAndOnCreatingWindowOnlyMovesFocus() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val w2 = (fixture.controller.openApp(APP_MAPS) as OpenAppResult.Opened).windowId
        fixture.input.readyCalls.clear()

        // When: focus a CREATING window.
        assertTrue(fixture.controller.focusWindow(w2))
        // Then: focus moved, no retarget (no content yet).
        assertEquals(w2, fixture.controller.state.focusedWindowId)
        assertTrue(fixture.input.readyCalls.isEmpty())

        // When: that window becomes ready.
        fixture.controller.onWindowContentReady(w2, 12, 1280, 720, 240)
        // Then: focused window's content-ready retargets input.
        assertEquals(listOf(ReadyCall(12, ContentSize(1280, 720))), fixture.input.readyCalls)

        // When: focus an already-running window again.
        fixture.input.readyCalls.clear()
        assertTrue(fixture.controller.focusWindow(w1))
        // Then: retarget to its display.
        assertEquals(listOf(ReadyCall(11, ContentSize(1280, 720))), fixture.input.readyCalls)

        // Unknown window id is reported as absent.
        assertFalse(fixture.controller.focusWindow(SpatialWindowId(999)))
    }

    @Test
    fun spatialClickRetargetsWindowBeforeInjectingAbsoluteContentPoint() = runBlocking {
        val fixture = runningWorkspaceFixture()
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        fixture.input.readyCalls.clear()
        fixture.input.pointerCalls.clear()

        fixture.controller.handleSpatialIntent(
            SpatialIntent.InjectContent(
                windowId = w1.value,
                contentX = 320f,
                contentY = 180f,
                action = PointerAction.CLICK,
                button = MouseButton.LEFT,
            ),
        )

        assertEquals(w1, fixture.controller.state.focusedWindowId)
        assertEquals(listOf(ReadyCall(11, ContentSize(1280, 720))), fixture.input.readyCalls)
        assertEquals(
            listOf(PointerCall(PointerAction.CLICK, MouseButton.LEFT, 320f, 180f)),
            fixture.input.pointerCalls,
        )
    }

    @Test
    fun closingFocusedWindowReleasesInputForceStopsAppAndRefocusesLatest() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val w2 = fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        fixture.openRunningWindow(APP_BROWSER, contentDisplayId = 13)
        // w3 (latest) is focused; close it.
        val w3 = fixture.controller.state.focusedWindowId!!
        fixture.input.readyCalls.clear()
        fixture.input.goneCount = 0

        // When
        assertTrue(fixture.controller.closeWindow(w3))

        // Then: focused death released input exactly once, refocused the
        // latest remaining window (w2), retargeted to its display, and
        // force-stopped only the closed app.
        assertEquals(1, fixture.input.goneCount)
        assertEquals(w2, fixture.controller.state.focusedWindowId)
        assertEquals(listOf(ReadyCall(12, ContentSize(1280, 720))), fixture.input.readyCalls)
        assertEquals(listOf(APP_BROWSER), fixture.launcher.stoppedApps)
        assertEquals(2, fixture.controller.state.windows.size)

        // The closed id is gone.
        assertFalse(fixture.controller.closeWindow(w3))
    }

    @Test
    fun closingNonFocusedWindowDoesNotTouchInput() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val w2 = fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        val w1 = fixture.controller.state.windows.first { it.id != w2 }.id
        assertTrue(fixture.controller.focusWindow(w1))
        fixture.input.readyCalls.clear()
        fixture.input.goneCount = 0

        // When
        assertTrue(fixture.controller.closeWindow(w2))

        // Then: no input redirects — the focused window kept its target.
        assertEquals(0, fixture.input.goneCount)
        assertTrue(fixture.input.readyCalls.isEmpty())
        assertEquals(w1, fixture.controller.state.focusedWindowId)
        assertEquals(listOf(APP_MAPS), fixture.launcher.stoppedApps)
    }

    @Test
    fun windowContentGoneRemovesWindowAndRefocusesWithoutForceStop() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val w2 = fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        fixture.input.goneCount = 0
        fixture.input.readyCalls.clear()

        // When: the focused window's VD dies.
        fixture.controller.onWindowContentGone(w2, 12)

        // Then: window removed, no force-stop (VD already dead), input
        // released once and retargeted to the remaining window.
        assertEquals(1, fixture.controller.state.windows.size)
        assertEquals(w1, fixture.controller.state.focusedWindowId)
        assertTrue(fixture.launcher.stoppedApps.isEmpty())
        assertEquals(1, fixture.input.goneCount)
        assertEquals(ReadyCall(11, ContentSize(1280, 720)), fixture.input.readyCalls.singleOrNull())

        // Stale callback for an already-removed window is a no-op.
        fixture.input.goneCount = 0
        fixture.controller.onWindowContentGone(w2, 12)
        assertEquals(0, fixture.input.goneCount)
        assertEquals(1, fixture.controller.state.windows.size)
    }

    @Test
    fun stopWorkspaceForceStopsAllWindowAppsAndClearsScene() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        fixture.openRunningWindow(APP_MAPS, contentDisplayId = 12)
        fixture.controller.updateCursor(CursorState(320f, 180f, visible = true, pressed = true))
        fixture.input.goneCount = 0

        // When
        fixture.controller.stopWorkspace()

        // Then
        assertIdleAndCleared(fixture.controller.state)
        assertEquals(listOf(APP_NOTES, APP_MAPS), fixture.launcher.stoppedApps)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.input.goneCount)
    }

    @Test
    fun hostDestroyedClearsSceneWithoutStoppingAlreadyDestroyingHost() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture(outputDisplayId = 25)
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 43)
        fixture.input.goneCount = 0

        // When
        fixture.controller.onHostDestroyed(25)

        // Then
        assertIdleAndCleared(fixture.controller.state)
        assertEquals(listOf(APP_NOTES), fixture.launcher.stoppedApps)
        assertEquals(0, fixture.host.stopCount)
        assertEquals(1, fixture.input.goneCount)
    }

    @Test
    fun staleHostDestroyedIsIgnored() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture(outputDisplayId = 26)
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 44)
        fixture.controller.updateCursor(CursorState(80f, 120f, visible = true))
        val cursor = fixture.controller.state.cursor

        // When
        fixture.controller.onHostDestroyed(100)

        // Then
        assertEquals(WorkspaceStatus.RUNNING, fixture.controller.state.status)
        assertEquals(26, fixture.controller.state.outputDisplayId)
        assertEquals(1, fixture.controller.state.windows.size)
        assertEquals(w1, fixture.controller.state.focusedWindowId)
        assertEquals(cursor, fixture.controller.state.cursor)
        assertEquals(0, fixture.host.stopCount)
    }

    @Test
    fun outputUnplugResetsEverythingAndReplugAcceptsNewWindows() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture(outputDisplayId = 5)
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 13)

        // When
        fixture.controller.onOutputDisconnected(5)

        // Then
        assertIdleAndCleared(fixture.controller.state)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(listOf(APP_NOTES), fixture.launcher.stoppedApps)

        // When: replug with a fresh dynamic display id.
        fixture.controller.onOutputConnected(21)
        val openBeforeStart = fixture.controller.openApp(APP_MAPS)

        // Then: session not started yet.
        assertEquals(OpenAppResult.Rejected(OpenAppResult.Reason.NOT_RUNNING), openBeforeStart)
        assertTrue(fixture.controller.startWorkspace())
        val openAfterStart = fixture.controller.openApp(APP_MAPS)
        assertTrue(openAfterStart is OpenAppResult.Opened)
        assertEquals(21, fixture.controller.state.outputDisplayId)
        assertNull(fixture.controller.state.contentDisplayId)
        assertEquals(2, fixture.host.startCount)
    }

    @Test
    fun updateCursorWritesOnlyFocusedWindowAndOnlyAfterContentReady() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture()
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 11)
        val w2 = (fixture.controller.openApp(APP_MAPS) as OpenAppResult.Opened).windowId
        // w2 focused but CREATING: cursor write must be dropped.
        fixture.controller.updateCursor(CursorState(1f, 2f, visible = true))
        assertNull(fixture.controller.state.cursor)
        assertNull(fixture.window(w2).cursor)

        // When: cursor moves while w1 focused.
        assertTrue(fixture.controller.focusWindow(w1))
        val cursor = CursorState(100f, 200f, visible = true, pressed = true)
        fixture.controller.updateCursor(cursor)

        // Then: only w1's cursor changed; w2 stays cursor-less.
        assertEquals(cursor, fixture.window(w1).cursor)
        assertEquals(cursor, fixture.controller.state.cursor)
        assertNull(fixture.window(w2).cursor)
    }

    @Test
    fun compatPropertiesFollowFocusedWindow() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture(outputDisplayId = 6)
        val w1 = fixture.openRunningWindow(APP_NOTES, contentDisplayId = 14)
        val w2 = fixture.openRunningWindow(APP_MAPS, contentDisplayId = 15)

        // Then: focused (w2) drives every compatibility property.
        assertEquals(w2, fixture.controller.state.focusedWindowId)
        assertEquals(15, fixture.controller.state.contentDisplayId)
        assertEquals(ContentSpec(1280, 720, 240), fixture.controller.state.content)
        assertEquals(APP_MAPS, fixture.controller.state.activeApp)
        assertEquals(2, fixture.controller.state.windows.size)

        // When: focus w1 — properties follow without any other change.
        assertTrue(fixture.controller.focusWindow(w1))
        assertEquals(14, fixture.controller.state.contentDisplayId)
        assertEquals(APP_NOTES, fixture.controller.state.activeApp)
        assertEquals(WorkspaceStatus.RUNNING, fixture.controller.state.status)

        // Render settings remain observable.
        fixture.controller.setRenderMode(RenderMode.SBS_DUPLICATE)
        fixture.controller.setAspectMode(AspectMode.FILL)
        fixture.controller.setRotation(ContentRotation.DEG_90)
        assertEquals(RenderMode.SBS_DUPLICATE, fixture.controller.state.renderMode)
        assertEquals(AspectMode.FILL, fixture.controller.state.aspectMode)
        assertEquals(ContentRotation.DEG_90, fixture.controller.state.rotation)
        assertEquals(6, fixture.controller.state.outputDisplayId)
        assertEquals(1, fixture.host.startCount)
    }

    @Test
    fun inFlightContentReadyCannotResurrectSessionStoppedDuringLaunch() = runBlocking {
        // Given
        val fixture = Fixture()
        fixture.launcher.launchDelayMs = 120
        fixture.controller.onOutputConnected(50)
        fixture.controller.startWorkspace()
        val id = (fixture.controller.openApp(APP_NOTES) as OpenAppResult.Opened).windowId

        // When: content-ready suspends inside the launch port; stop queues behind the mutex.
        val scope = CoroutineScope(Dispatchers.Default)
        val readyJob = scope.launch { fixture.controller.onWindowContentReady(id, 60, 1280, 720, 240) }
        delay(40)
        fixture.controller.stopWorkspace()
        readyJob.join()

        // Then: session stays Idle — the stale window must not survive.
        assertIdleAndCleared(fixture.controller.state)
        // The app was actually launched, so cleanup must have caught it.
        assertEquals(listOf(APP_NOTES), fixture.launcher.stoppedApps)
    }

    @Test
    fun concurrentStopAndHostDestroyForceStopsWindowAppsExactlyOnce() = runBlocking {
        // Given
        val fixture = runningWorkspaceFixture(outputDisplayId = 70)
        fixture.openRunningWindow(APP_NOTES, contentDisplayId = 80)
        fixture.openRunningWindow(APP_MAPS, contentDisplayId = 81)

        // When
        val scope = CoroutineScope(Dispatchers.Default)
        val a = scope.launch { fixture.controller.stopWorkspace() }
        val b = scope.launch { fixture.controller.onHostDestroyed(70) }
        a.join()
        b.join()

        // Then: each window app force-stopped exactly once.
        assertEquals(2, fixture.launcher.stoppedApps.size)
        assertEquals(fixture.launcher.stoppedApps.toSet(), setOf(APP_NOTES, APP_MAPS))
    }

    @Test
    fun replacingInputSessionSuppressesCallbacksAlreadyQueuedForOldSession() = runBlocking {
        // Given
        val dispatcher = QueuedDispatcher()
        val store = WorkspaceStore()
        val controller = WorkspaceController(
            store = store,
            host = FakeWorkspaceHost(startSucceeds = true),
            appLauncher = FakeWorkspaceAppLauncher(launchSucceeds = true),
            scope = CoroutineScope(dispatcher),
        )
        val oldSession = FakeInputSession {
            controller.updateCursor(CursorState(10f, 20f, visible = true))
        }
        controller.setInputSession(oldSession)
        controller.onOutputConnected(51)
        controller.startWorkspace()
        val id = (controller.openApp(APP_NOTES) as OpenAppResult.Opened).windowId
        controller.onWindowContentReady(id, 61, 1280, 720, 240)

        // When
        controller.setInputSession(FakeInputSession())
        dispatcher.runQueued()

        // Then
        assertEquals(0, oldSession.readyCalls.size)
        assertNull(store.state.value.cursor)
    }

    private fun runningWorkspaceFixture(outputDisplayId: Int = 7): Fixture = runBlocking {
        val fixture = Fixture()
        fixture.controller.onOutputConnected(outputDisplayId)
        assertTrue(fixture.controller.startWorkspace())
        fixture
    }

    private suspend fun Fixture.openRunningWindow(
        app: ActiveApp,
        contentDisplayId: Int,
    ): SpatialWindowId {
        val id = (controller.openApp(app) as OpenAppResult.Opened).windowId
        controller.onWindowContentReady(id, contentDisplayId, 1280, 720, 240)
        return id
    }

    private fun Fixture.window(id: SpatialWindowId): SpatialWindowState =
        controller.state.scene.windows.getValue(id)

    private fun assertIdleAndCleared(state: WorkspaceState) {
        assertEquals(WorkspaceStatus.IDLE, state.status)
        assertNull(state.outputDisplayId)
        assertNull(state.contentDisplayId)
        assertNull(state.content)
        assertNull(state.activeApp)
        assertNull(state.cursor)
        assertTrue(state.windows.isEmpty())
        assertNull(state.focusedWindowId)
        assertFalse(state.started)
    }

    private class Fixture(
        launchSucceeds: Boolean = true,
        hostStartSucceeds: Boolean = true,
        rootAvailable: Boolean = false,
    ) {
        val host = FakeWorkspaceHost(hostStartSucceeds)
        val launcher = FakeWorkspaceAppLauncher(launchSucceeds)
        val root = FakeWorkspaceRoot(rootAvailable)
        val input = FakeInputSession()
        val controller = WorkspaceController(
            WorkspaceStore(),
            host,
            launcher,
            root,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ).also { it.setInputSession(input) }
    }

    private class FakeWorkspaceHost(
        var startSucceeds: Boolean,
    ) : WorkspaceHostPort {
        var startCount = 0
        var stopCount = 0
        val startedOutputDisplayIds = mutableListOf<Int>()

        override fun start(outputDisplayId: Int): Boolean {
            startCount += 1
            startedOutputDisplayIds += outputDisplayId
            return startSucceeds
        }

        override fun stop() {
            stopCount += 1
        }
    }

    private class FakeWorkspaceAppLauncher(
        private val launchSucceeds: Boolean,
    ) : WorkspaceAppLauncherPort {
        val requests = mutableListOf<LaunchRequest>()
        val stoppedApps = mutableListOf<ActiveApp>()
        var launchDelayMs: Long = 0

        override suspend fun launch(app: ActiveApp, contentDisplayId: Int): Boolean {
            if (launchDelayMs > 0) delay(launchDelayMs)
            requests += LaunchRequest(app, contentDisplayId)
            return launchSucceeds
        }

        override suspend fun stop(app: ActiveApp) {
            stoppedApps += app
        }
    }

    private class FakeWorkspaceRoot(
        private val available: Boolean,
    ) : WorkspaceRootPort {
        var probeCount = 0

        override suspend fun isAvailable(): Boolean {
            probeCount += 1
            return available
        }
    }

    private class FakeInputSession(
        private val onReady: () -> Unit = {},
    ) : InputSession {
        val readyCalls = mutableListOf<ReadyCall>()
        var goneCount = 0
        val pointerCalls = mutableListOf<PointerCall>()
        val scrollCalls = mutableListOf<Pair<Float, Float>>()

        override suspend fun onContentReady(contentDisplayId: Int, size: ContentSize) {
            readyCalls += ReadyCall(contentDisplayId, size)
            onReady()
        }

        override suspend fun onPointer(
            action: PointerAction,
            button: MouseButton,
            contentX: Float,
            contentY: Float,
        ) {
            pointerCalls += PointerCall(action, button, contentX, contentY)
        }

        override suspend fun onScroll(dx: Float, dy: Float) {
            scrollCalls += dx to dy
        }

        override suspend fun onContentGone() {
            goneCount += 1
        }

        override suspend fun dispose() = Unit
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private data class PointerCall(
        val action: PointerAction,
        val button: MouseButton,
        val x: Float,
        val y: Float,
    )

    private data class LaunchRequest(
        val app: ActiveApp,
        val contentDisplayId: Int,
    )

    private data class ReadyCall(
        val contentDisplayId: Int,
        val size: ContentSize,
    )

    private companion object {
        val APP_NOTES = ActiveApp("com.example.notes", "com.example.notes.MainActivity", "Notes")
        val APP_MAPS = ActiveApp("com.example.maps", "com.example.maps.MainActivity", "Maps")
        val APP_BROWSER = ActiveApp("com.example.browser", "com.example.browser.MainActivity", "Browser")
        val APP_VIDEO = ActiveApp("com.example.video", "com.example.video.MainActivity", "Video")
    }
}
