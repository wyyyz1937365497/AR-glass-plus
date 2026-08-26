package com.example.ar_glass_plus.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WorkspaceSession {
    val store = WorkspaceStore()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var controller: WorkspaceController? = null

    fun createController(
        host: WorkspaceHostPort,
        appLauncher: WorkspaceAppLauncherPort,
        root: WorkspaceRootPort = WorkspaceRootPort { false },
    ): WorkspaceController = WorkspaceController(store, host, appLauncher, root)

    fun registerController(controller: WorkspaceController) {
        this.controller = controller
    }

    fun controllerOrNull(): WorkspaceController? = controller

    /**
     * Host teardown runs outside the activity lifecycle. Host destruction
     * implies every window's VirtualDisplay died — onHostDestroyed is the
     * single funnel for the whole scene.
     */
    fun launchHostCleanup(
        controller: WorkspaceController,
        outputDisplayId: Int,
    ): Job = processScope.launch {
        controller.onHostDestroyed(outputDisplayId)
    }

    fun clearControllerForTests() {
        controller = null
    }
}
