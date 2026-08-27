package com.example.ar_glass_plus

import android.app.Application
import com.example.ar_glass_plus.display.ExternalDisplayController
import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.api.UinputInputBackend
import com.example.ar_glass_plus.input.mouse.MouseController
import com.example.ar_glass_plus.input.touchpad.TrackpadConfig
import com.example.ar_glass_plus.input.touchpad.TrackpadGestureEngine
import com.example.ar_glass_plus.root.RootShellImpl
import com.example.ar_glass_plus.workspace.AndroidWorkspaceAppLauncher
import com.example.ar_glass_plus.workspace.AndroidWorkspaceHostPort
import com.example.ar_glass_plus.workspace.AndroidWorkspaceRootPort
import com.example.ar_glass_plus.workspace.RealInputSession
import com.example.ar_glass_plus.workspace.WorkspaceController
import com.example.ar_glass_plus.workspace.WorkspaceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process entry point. Owns the process-stable workspace controller and the
 * physical-display controller so Activity recreation (rotation, config change,
 * task migration) never produces a second controller while the rendered-host
 * Activity still holds the original one (review blocker: controller split-brain).
 */
class App : Application() {

    lateinit var displayController: ExternalDisplayController
        private set
    lateinit var shell: RootShellImpl
        private set
    lateinit var uinputBackend: UinputInputBackend
        private set
    lateinit var cursorController: CursorController
        private set
    lateinit var mouseController: MouseController
        private set
    lateinit var engine: TrackpadGestureEngine
        private set

    val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var inputSession: RealInputSession


    /** Latest spatial renderer telemetry (published by RenderDisplayActivity). */
    @Volatile
    var renderStats: com.example.ar_glass_plus.render.gl.SpatialRenderStats =
        com.example.ar_glass_plus.render.gl.SpatialRenderStats()
        private set

    fun publishRenderStats(stats: com.example.ar_glass_plus.render.gl.SpatialRenderStats) {
        renderStats = stats
    }

    override fun onCreate() {
        super.onCreate()
        displayController = ExternalDisplayController(applicationContext)
        shell = RootShellImpl()
        val host = AndroidWorkspaceHostPort(applicationContext, displayController)
        val appLauncher = AndroidWorkspaceAppLauncher(applicationContext, shell)
        val root = AndroidWorkspaceRootPort(shell)
        val controller = WorkspaceSession.createController(host, appLauncher, root)
        WorkspaceSession.registerController(controller)
        createInputStack(controller)
        controller.setInputSession(inputSession)
    }

    private fun createInputStack(controller: WorkspaceController) {
        uinputBackend = UinputInputBackend(applicationContext)
        cursorController = CursorController(
            onContentSize = {
                WorkspaceSession.store.state.value.content?.let { it.width to it.height }
            },
            cursorProvider = { WorkspaceSession.store.state.value.cursor },
            onCursorChanged = controller::updateCursor,
        )
        mouseController = MouseController(
            backend = uinputBackend,
            cursor = cursorController,
            cursorProvider = { WorkspaceSession.store.state.value.cursor },
            scope = inputScope,
        )
        engine = TrackpadGestureEngine(
            config = TrackpadConfig(applicationContext),
            scope = inputScope,
        )
        inputSession = RealInputSession(
            backend = uinputBackend,
            cursor = cursorController,
            mouse = mouseController,
            engine = engine,
        )
    }
}
