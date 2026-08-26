package com.example.ar_glass_plus.workspace

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlinx.coroutines.runBlocking

class WorkspaceSessionTest {

    @After
    fun clearRegistration() {
        WorkspaceSession.clearControllerForTests()
    }

    @Test
    fun registeredControllerIsAvailableProcessWide() {
        // Given
        val controller = createController()

        // When
        WorkspaceSession.registerController(controller)

        // Then
        assertSame(controller, WorkspaceSession.controllerOrNull())
    }

    @Test
    fun clearForTestsRemovesRegisteredController() {
        // Given
        WorkspaceSession.registerController(createController())

        // When
        WorkspaceSession.clearControllerForTests()

        // Then
        assertNull(WorkspaceSession.controllerOrNull())
    }

    @Test
    fun hostCleanupCompletesOutsideActivityLifecycle() = runBlocking {
        // Given
        val controller = createController()
        WorkspaceSession.registerController(controller)
        controller.onOutputConnected(12)
        controller.startWorkspace()

        // When
        WorkspaceSession.launchHostCleanup(controller, 12).join()

        // Then
        assertSame(WorkspacePhase.Idle, controller.state.phase)
    }

    private fun createController(): WorkspaceController = WorkspaceSession.createController(
        host = object : WorkspaceHostPort {
            override fun start(outputDisplayId: Int): Boolean = true
            override fun stop() = Unit
        },
        appLauncher = object : WorkspaceAppLauncherPort {
            override suspend fun launch(app: ActiveApp, contentDisplayId: Int): Boolean = true
        },
    )
}
