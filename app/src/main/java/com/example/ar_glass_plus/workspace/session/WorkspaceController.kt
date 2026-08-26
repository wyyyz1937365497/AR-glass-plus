package com.example.ar_glass_plus.workspace

import android.util.Log
import com.example.ar_glass_plus.input.CursorState
import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.Vec3
import com.example.ar_glass_plus.render.geometry.AspectMode
import com.example.ar_glass_plus.render.geometry.ContentRotation
import com.example.ar_glass_plus.render.geometry.RenderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface WorkspaceHostPort {
    fun start(outputDisplayId: Int): Boolean

    fun stop()
}

interface WorkspaceAppLauncherPort {
    suspend fun launch(app: ActiveApp, contentDisplayId: Int): Boolean

    suspend fun stop(app: ActiveApp) = Unit
}

fun interface WorkspaceRootPort {
    suspend fun isAvailable(): Boolean
}

sealed interface OpenAppResult {
    enum class Reason { NOT_RUNNING, NO_FREE_SLOT }

    data class Opened(val windowId: SpatialWindowId) : OpenAppResult

    data class FocusedExisting(val windowId: SpatialWindowId) : OpenAppResult

    data class Rejected(val reason: Reason) : OpenAppResult
}

/**
 * Multi-window workspace state machine. One SpatialWindow = one
 * VirtualDisplay + one OES input; this controller owns window lifecycle,
 * focus, and the single input-retarget path (InputSession.onContentReady /
 * onContentGone). Never touches GL or Android display types — the render
 * host (RenderDisplayActivity) materializes windows from store diffs.
 */
class WorkspaceController(
    private val store: WorkspaceStore,
    private val host: WorkspaceHostPort,
    private val appLauncher: WorkspaceAppLauncherPort,
    private val root: WorkspaceRootPort = WorkspaceRootPort { false },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val inputMutex = Mutex()
    private var nextWindowId = 1L
    private val appPendingCleanup = linkedSetOf<ActiveApp>()
    private var inputSession: InputSession? = null
    private var inputSessionGeneration = 0L

    val state: WorkspaceState
        get() = store.state.value

    @Synchronized
    fun setInputSession(session: InputSession) {
        if (inputSession === session) return
        inputSession = session
        inputSessionGeneration += 1
    }

    fun onOutputConnected(outputDisplayId: Int) {
        if (state.phase !is WorkspacePhase.Idle) return

        store.update {
            it.copy(
                phase = WorkspacePhase.OutputReady(outputDisplayId),
                scene = SpatialSceneState(),
                started = false,
            )
        }
    }

    fun startWorkspace(): Boolean {
        val current = state.phase as? WorkspacePhase.OutputReady ?: return false
        if (state.started) return false

        val startSucceeded = host.start(current.outputDisplayId)
        if (startSucceeded) {
            store.update { it.copy(started = true) }
        }
        return startSucceeded
    }

    /**
     * Open [app] in a new window (or focus its existing window when the
     * package is already open — force-stop is package-wide, so a package
     * can never own two windows).
     */
    suspend fun openApp(app: ActiveApp): OpenAppResult = mutex.withLock {
        if (!state.started || state.phase !is WorkspacePhase.OutputReady) {
            return@withLock OpenAppResult.Rejected(OpenAppResult.Reason.NOT_RUNNING)
        }
        val existing = state.scene.windows.values.firstOrNull { it.app.packageName == app.packageName }
        if (existing != null) {
            focusWindowLocked(existing.id)
            return@withLock OpenAppResult.FocusedExisting(existing.id)
        }
        if (state.scene.windows.size >= SpatialWindowModel.MAX_WINDOWS) {
            return@withLock OpenAppResult.Rejected(OpenAppResult.Reason.NO_FREE_SLOT)
        }

        // Gate 2: pose (not slot) is the layout truth — preset by creation
        // order for the fixed test scene.
        val pose = SpatialWindowModel.presetPose(state.scene.windows.size)
        val id = SpatialWindowId(nextWindowId++)
        store.update { st ->
            st.copy(
                scene = st.scene.copy(
                    windows = st.scene.windows + (id to SpatialWindowState(id = id, app = app, pose = pose)),
                    focusedWindowId = id,
                ),
            )
        }
        Log.i(TAG, "openApp pkg=${app.packageName} -> window ${id.value} at ${pose.position}")
        OpenAppResult.Opened(id)
    }

    /**
     * Debug/Gate-2 pose nudge for the focused window: translate by
     * (dx,dy,dz) meters, apply extra yaw/pitch/roll degrees, resize by
     * (dw,dh) meters. Pure state — the renderer follows the next store
     * emission. No-op without a focused window.
     */
    fun adjustFocusedWindow(
        dxMeters: Float = 0f,
        dyMeters: Float = 0f,
        dzMeters: Float = 0f,
        dyawDeg: Float = 0f,
        dpitchDeg: Float = 0f,
        drollDeg: Float = 0f,
        dWidthMeters: Float = 0f,
        dHeightMeters: Float = 0f,
    ) {
        store.update { st ->
            val id = st.scene.focusedWindowId ?: return@update st
            val window = st.scene.windows[id] ?: return@update st
            val p = window.pose
            val next = p.copy(
                position = p.position + Vec3(dxMeters, dyMeters, dzMeters),
                orientation = (Quat.fromEulerDegrees(dyawDeg, dpitchDeg, drollDeg) * p.orientation).normalize(),
                widthMeters = (p.widthMeters + dWidthMeters).coerceAtLeast(0.05f),
                heightMeters = (p.heightMeters + dHeightMeters).coerceAtLeast(0.05f),
            )
            updateWindow(st, id) { it.copy(pose = next) }
        }
    }

    /**
     * Focus a window. If its content is already live, the input session is
     * retargeted through the standard onContentReady path; a CREATING
     * window retargets later when its content becomes ready.
     */
    suspend fun focusWindow(id: SpatialWindowId): Boolean = mutex.withLock {
        focusWindowLocked(id)
    }

    /** Close a window: release input (if focused), force-stop its app, refocus. */
    suspend fun closeWindow(id: SpatialWindowId): Boolean = mutex.withLock {
        removeWindowLocked(id, stopApp = true)
    }

    /**
     * Host callback: the window's VirtualDisplay reached Running. Marks the
     * window content-ready, retargets input when focused, launches the app;
     * a failed launch tears the window down again (nothing to force-stop).
     */
    suspend fun onWindowContentReady(
        windowId: SpatialWindowId,
        contentDisplayId: Int,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) = mutex.withLock {
        if (!state.started) return@withLock
        if (state.scene.windows[windowId] == null) return@withLock

        val window = state.scene.windows.getValue(windowId)
        store.update { st ->
            updateWindow(st, windowId) {
                it.copy(
                    lifecycle = WindowLifecycle.CONTENT_READY,
                    contentDisplayId = contentDisplayId,
                    content = ContentSpec(width, height, densityDpi),
                )
            }
        }
        if (state.scene.focusedWindowId == windowId) {
            launchInputCallback { session ->
                session.onContentReady(contentDisplayId, ContentSize(width, height))
            }
        }
        if (appLauncher.launch(window.app, contentDisplayId)) {
            store.update { st ->
                updateWindow(st, windowId) { it.copy(lifecycle = WindowLifecycle.RUNNING) }
            }
            appPendingCleanup += window.app
            Log.i(TAG, "window ${windowId.value} running on display $contentDisplayId (${window.app.packageName})")
        } else {
            Log.w(TAG, "launch failed for window ${windowId.value} (${window.app.packageName}); closing")
            removeWindowLocked(windowId, stopApp = false)
        }
    }

    /**
     * Host callback: the window's VirtualDisplay died (or failed to create —
     * [contentDisplayId] is informational only). Removes the window without
     * force-stop; the display is already gone.
     */
    suspend fun onWindowContentGone(windowId: SpatialWindowId, contentDisplayId: Int) = mutex.withLock {
        if (state.scene.windows[windowId] == null) return@withLock
        Log.i(TAG, "window ${windowId.value} contentGone display $contentDisplayId")
        removeWindowLocked(windowId, stopApp = false)
    }

    suspend fun stopWorkspace() = mutex.withLock {
        if (state.phase is WorkspacePhase.Idle) return@withLock

        val hadContent = state.scene.windows.values.any { it.content != null }
        stopActiveAppLocked()
        host.stop()
        store.update {
            it.copy(phase = WorkspacePhase.Idle, scene = SpatialSceneState(), started = false)
        }
        if (hadContent) {
            launchInputCallback(InputSession::onContentGone)
        }
        Log.i(TAG, "workspace stopped")
    }

    suspend fun onOutputDisconnected(outputDisplayId: Int) {
        if (state.outputDisplayId != outputDisplayId) return
        stopWorkspace()
    }

    suspend fun onHostDestroyed(outputDisplayId: Int) = mutex.withLock {
        if (state.outputDisplayId != outputDisplayId) return@withLock

        val hadContent = state.scene.windows.values.any { it.content != null }
        stopActiveAppLocked()
        store.update {
            it.copy(phase = WorkspacePhase.Idle, scene = SpatialSceneState(), started = false)
        }
        if (hadContent) {
            launchInputCallback(InputSession::onContentGone)
        }
        Log.i(TAG, "host destroyed")
    }

    fun setRenderMode(renderMode: RenderMode) {
        store.update { it.copy(renderMode = renderMode) }
    }

    fun setAspectMode(aspectMode: AspectMode) {
        store.update { it.copy(aspectMode = aspectMode) }
    }

    fun setRotation(rotation: ContentRotation) {
        store.update { it.copy(rotation = rotation) }
    }

    /** Cursor lives on the focused window, and only once its content exists. */
    fun updateCursor(cursor: CursorState?) {
        store.update { state ->
            val id = state.scene.focusedWindowId ?: return@update state
            val window = state.scene.windows[id] ?: return@update state
            if (window.content == null) return@update state
            state.copy(
                scene = state.scene.copy(
                    windows = state.scene.windows + (id to window.copy(cursor = cursor)),
                ),
            )
        }
    }

    suspend fun probeRootAvailability() {
        val available = root.isAvailable()
        store.update { it.copy(rootAvailable = available) }
    }

    fun setRootAvailable(available: Boolean) {
        store.update { it.copy(rootAvailable = available) }
    }

    fun setInputAvailable(available: Boolean) {
        store.update { it.copy(inputAvailable = available) }
    }

    private fun focusWindowLocked(id: SpatialWindowId): Boolean {
        val window = state.scene.windows[id] ?: return false
        if (state.scene.focusedWindowId == id) return true

        store.update { st ->
            st.copy(scene = st.scene.copy(focusedWindowId = id))
        }
        retargetInputLocked(window)
        Log.i(TAG, "focus window ${id.value} (${window.app.packageName})")
        return true
    }

    /**
     * Shared teardown: releases input first when the focused window dies
     * (cancel gesture + release buttons before anything else), optionally
     * force-stops the app, removes the window, then focuses the most
     * recently created remaining window.
     */
    private suspend fun removeWindowLocked(windowId: SpatialWindowId, stopApp: Boolean): Boolean {
        val window = state.scene.windows[windowId] ?: return false
        val wasFocused = state.scene.focusedWindowId == windowId

        if (wasFocused) {
            launchInputCallback(InputSession::onContentGone)
        }
        if (stopApp && appPendingCleanup.remove(window.app)) {
            appLauncher.stop(window.app)
        }
        store.update { st ->
            st.copy(
                scene = st.scene.copy(
                    windows = st.scene.windows - windowId,
                    focusedWindowId = if (wasFocused) {
                        st.scene.windows.keys.lastOrNull { it != windowId }
                    } else {
                        st.scene.focusedWindowId
                    },
                ),
            )
        }
        Log.i(TAG, "window ${windowId.value} removed (${window.app.packageName})")
        if (wasFocused) {
            state.scene.focusedWindowId?.let { successorId ->
                state.scene.windows[successorId]?.let { retargetInputLocked(it) }
            }
        }
        return true
    }

    /** Standard retarget primitive — the only path that re-points input. */
    private fun retargetInputLocked(window: SpatialWindowState) {
        val displayId = window.contentDisplayId ?: return
        val content = window.content ?: return
        launchInputCallback { session ->
            session.onContentReady(displayId, ContentSize(content.width, content.height))
        }
    }

    private fun launchInputCallback(callback: suspend (InputSession) -> Unit) {
        val snapshot = synchronized(this) {
            val session = inputSession ?: return
            session to inputSessionGeneration
        }
        scope.launch {
            inputMutex.withLock {
                val isCurrent = synchronized(this@WorkspaceController) {
                    inputSession === snapshot.first && inputSessionGeneration == snapshot.second
                }
                if (isCurrent) callback(snapshot.first)
            }
        }
    }

    private suspend fun stopActiveAppLocked() {
        if (appPendingCleanup.isEmpty()) return
        val apps = appPendingCleanup.toList()
        appPendingCleanup.clear()
        apps.forEach { appLauncher.stop(it) }
    }

    private fun updateWindow(
        st: WorkspaceState,
        windowId: SpatialWindowId,
        transform: (SpatialWindowState) -> SpatialWindowState,
    ): WorkspaceState {
        val window = st.scene.windows[windowId] ?: return st
        return st.copy(
            scene = st.scene.copy(windows = st.scene.windows + (windowId to transform(window))),
        )
    }

    private companion object {
        const val TAG = "WorkspaceCtrl"
    }
}
