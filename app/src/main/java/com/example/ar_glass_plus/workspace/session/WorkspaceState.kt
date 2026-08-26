package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorState
import com.example.ar_glass_plus.render.geometry.AspectMode
import com.example.ar_glass_plus.render.geometry.ContentRotation
import com.example.ar_glass_plus.render.geometry.RenderMode

data class ContentSpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

data class ActiveApp(
    val packageName: String,
    val launcherClassName: String?,
    val label: String,
)

enum class WorkspaceStatus {
    IDLE,
    OUTPUT_READY,
    CONTENT_READY,
    RUNNING,
}

/**
 * Workspace-level phase only: Idle → OutputReady(output). Window-level
 * lifecycle (CREATING/CONTENT_READY/RUNNING) lives in [SpatialWindowState].
 */
internal sealed interface WorkspacePhase {
    data object Idle : WorkspacePhase

    data class OutputReady(val outputDisplayId: Int) : WorkspacePhase
}

/**
 * Multi-window workspace state. Single-window readers (cursor provider,
 * session card, input gating) use the derived compatibility properties,
 * which all resolve through the focused window.
 */
class WorkspaceState internal constructor(
    internal val phase: WorkspacePhase = WorkspacePhase.Idle,
    val scene: SpatialSceneState = SpatialSceneState(),
    val renderMode: RenderMode = RenderMode.PASSTHROUGH_2D,
    val aspectMode: AspectMode = AspectMode.FIT,
    val rotation: ContentRotation = ContentRotation.DEG_0,
    val rootAvailable: Boolean = false,
    val inputAvailable: Boolean = false,
    /** True while a render session hosts this workspace; gates openApp. */
    val started: Boolean = false,
) {
    val status: WorkspaceStatus
        get() = when {
            phase is WorkspacePhase.Idle -> WorkspaceStatus.IDLE
            scene.windows.values.any { it.lifecycle == WindowLifecycle.RUNNING } ->
                WorkspaceStatus.RUNNING
            scene.windows.isNotEmpty() -> WorkspaceStatus.CONTENT_READY
            else -> WorkspaceStatus.OUTPUT_READY
        }

    val outputDisplayId: Int?
        get() = (phase as? WorkspacePhase.OutputReady)?.outputDisplayId

    val windows: Collection<SpatialWindowState>
        get() = scene.windows.values

    val focusedWindowId: SpatialWindowId?
        get() = scene.focusedWindowId

    val focusedWindow: SpatialWindowState?
        get() = focusedWindowId?.let { scene.windows[it] }

    val contentDisplayId: Int?
        get() = focusedWindow?.contentDisplayId

    val content: ContentSpec?
        get() = focusedWindow?.content

    val activeApp: ActiveApp?
        get() = focusedWindow?.app

    val cursor: CursorState?
        get() = focusedWindow?.cursor

    internal fun copy(
        phase: WorkspacePhase = this.phase,
        scene: SpatialSceneState = this.scene,
        renderMode: RenderMode = this.renderMode,
        aspectMode: AspectMode = this.aspectMode,
        rotation: ContentRotation = this.rotation,
        rootAvailable: Boolean = this.rootAvailable,
        inputAvailable: Boolean = this.inputAvailable,
        started: Boolean = this.started,
    ): WorkspaceState = WorkspaceState(
        phase = phase,
        scene = scene,
        renderMode = renderMode,
        aspectMode = aspectMode,
        rotation = rotation,
        rootAvailable = rootAvailable,
        inputAvailable = inputAvailable,
        started = started,
    )
}
