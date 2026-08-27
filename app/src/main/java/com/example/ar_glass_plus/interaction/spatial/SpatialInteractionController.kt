package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.SpatialPoseRef
import com.example.ar_glass_plus.render.spatial.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the spatial pointer is doing right now. */
enum class SpatialPointerMode {
    /** Hovering/free: clicks pick windows, drags on content inject Android. */
    HOVER,

    /** Active window manipulation session (move/resize/rotate). */
    MANIPULATING,
}

/** State of the spatial pointer, consumed by UI + renderer (cursor ring). */
data class SpatialPointerState(
    val mode: SpatialPointerMode = SpatialPointerMode.HOVER,
    /** Window under the pointer, if any (hover focus candidate). */
    val hoveredWindowId: Long? = null,
    /** Window being manipulated, if any. */
    val activeWindowId: Long? = null,
)

/** One frame's camera + projection inputs for the controller. */
data class SpatialFrameParams(
    val camera: SpatialCamera,
    val viewProjection: Mat4,
    val outputWidthPx: Float,
    val outputHeightPx: Float,
)

/** Output intents the controller emits; the workspace layer applies them. */
sealed interface SpatialIntent {
    /** Focus (and optionally open) this window. */
    data class Focus(val windowId: Long) : SpatialIntent

    /** Inject an Android pointer event at content pixels of this window. */
    data class InjectContent(
        val windowId: Long,
        val contentX: Float,
        val contentY: Float,
        val kind: ContentPointerKind,
    ) : SpatialIntent

    /** Telemetry-only: hover position update (renderer cursor ring). */
    data class Hover(val windowId: Long?, val localPoint: Vec3?) : SpatialIntent
}

enum class ContentPointerKind { MOVE, DOWN, UP, CLICK, DOUBLE_CLICK }

/** A window manipulation in progress. */
internal sealed interface ManipulationSession {
    val windowId: Long

    data class Move(
        override val windowId: Long,
        /** View-aligned plane the drag lives on (normal = camera forward). */
        val planeOrigin: Vec3,
        val planeNormal: Vec3,
        val grabLocalOffset: Vec3,
        val startWindowPosition: Vec3,
    ) : ManipulationSession

    data class Resize(
        override val windowId: Long,
        val startWidth: Float,
        val startHeight: Float,
        val startLocal: Vec3,
    ) : ManipulationSession

    data class Rotate(
        override val windowId: Long,
        val startOrientation: Quat,
        val startPointerPx: Float,
        val startPointerPy: Float,
    ) : ManipulationSession
}

/**
 * Spatial interaction state machine. Consumes screen-space pointer events
 * (from the tablet trackpad surface) against the 3D scene and emits
 * [SpatialIntent]s. Knows nothing about GL, displays, or uinput — the
 * workspace layer interprets the intents.
 *
 * Input model (trackpad-relative → screen ray):
 *  - move ......... hover update; during a session, drives the session
 *  - tap .......... pick: focus window / click content
 *  - drag start ... on title bar → MOVE; on handle → RESIZE; on content →
 *                   Android drag (inject DOWN + moves); on border → ROTATE
 *  - drag move .... session update or Android inject
 *  - drag end ..... session commit / Android UP
 */
class SpatialInteractionController(
    private val emitIntent: (SpatialIntent) -> Unit,
    private val readPose: (Long) -> SpatialPoseRef?,
) {

    private val _state = MutableStateFlow(SpatialPointerState())
    val state: StateFlow<SpatialPointerState> = _state.asStateFlow()

    private var session: ManipulationSession? = null
    private var frameParams: SpatialFrameParams? = null
    private var lastRay: Ray3? = null
    private var pendingContentDown: SpatialHit.Content? = null

    // ── frame plumbing ──

    /** Call each frame (renderer) BEFORE pointer events of that frame. */
    fun onFrame(params: SpatialFrameParams) {
        frameParams = params
    }

    // ── pointer events (screen pixels, top-left origin) ──

    fun onPointerMove(px: Float, py: Float) {
        val params = frameParams ?: return
        val ray = SpatialHitTest.rayFromPixelWithProjection(px, py, params.outputWidthPx, params.outputHeightPx, params.camera, params.viewProjection) ?: return
        lastRay = ray

        val s = session
        if (s != null) {
            updateSession(s, ray, px, py, params)
            return
        }

        val hit = SpatialSceneQuery.hitTest(ray, currentTargets())
        if (hit == null) {
            if (_state.value.hoveredWindowId != null) {
                _state.value = _state.value.copy(hoveredWindowId = null)
                emitIntent(SpatialIntent.Hover(null, null))
            }
            return
        }
        if (_state.value.hoveredWindowId != hit.windowId) {
            _state.value = _state.value.copy(hoveredWindowId = hit.windowId)
        }
        when (hit) {
            is SpatialHit.Content -> emitIntent(SpatialIntent.Hover(hit.windowId, hit.localPoint))
            else -> emitIntent(SpatialIntent.Hover(hit.windowId, hit.localPoint))
        }
    }

    fun onPointerDown(px: Float, py: Float) {
        val params = frameParams ?: return
        val ray = SpatialHitTest.rayFromPixelWithProjection(px, py, params.outputWidthPx, params.outputHeightPx, params.camera, params.viewProjection) ?: return
        lastRay = ray
        val hit = SpatialSceneQuery.hitTest(ray, currentTargets()) ?: return

        emitIntent(SpatialIntent.Focus(hit.windowId))

        when (hit) {
            is SpatialHit.Content -> {
                pendingContentDown = hit
                emitIntent(
                    SpatialIntent.InjectContent(hit.windowId, hit.contentX, hit.contentY, ContentPointerKind.DOWN),
                )
            }
            is SpatialHit.TitleBar -> {
                val pose = readPose(hit.windowId) ?: return
                session = ManipulationSession.Move(
                    windowId = hit.windowId,
                    planeOrigin = pose.position,
                    planeNormal = params.camera.orientation.rotate(Vec3(0f, 0f, -1f)),
                    grabLocalOffset = hit.localPoint,
                    startWindowPosition = pose.position,
                )
                _state.value = _state.value.copy(mode = SpatialPointerMode.MANIPULATING, activeWindowId = hit.windowId)
            }
            is SpatialHit.ResizeHandle -> {
                val pose = readPose(hit.windowId) ?: return
                session = ManipulationSession.Resize(
                    windowId = hit.windowId,
                    startWidth = pose.widthMeters,
                    startHeight = pose.heightMeters,
                    startLocal = hit.localPoint,
                )
                _state.value = _state.value.copy(mode = SpatialPointerMode.MANIPULATING, activeWindowId = hit.windowId)
            }
            is SpatialHit.Border -> {
                val pose = readPose(hit.windowId) ?: return
                session = ManipulationSession.Rotate(
                    windowId = hit.windowId,
                    startOrientation = pose.orientation,
                    startPointerPx = px,
                    startPointerPy = py,
                )
                _state.value = _state.value.copy(mode = SpatialPointerMode.MANIPULATING, activeWindowId = hit.windowId)
            }
        }
    }

    fun onPointerUp() {
        val down = pendingContentDown
        if (down != null) {
            pendingContentDown = null
            emitIntent(
                SpatialIntent.InjectContent(down.windowId, down.contentX, down.contentY, ContentPointerKind.UP),
            )
        }
        if (session != null) {
            session = null
            _state.value = _state.value.copy(mode = SpatialPointerMode.HOVER, activeWindowId = null)
        }
    }

    /** Cancel any session without emitting UP (used on teardown/re-target). */
    fun cancelSession() {
        session = null
        pendingContentDown = null
        _state.value = SpatialPointerState()
    }

    // ── session updates ──

    private fun updateSession(s: ManipulationSession, ray: Ray3, px: Float, py: Float, params: SpatialFrameParams) {
        val pose = readPose(s.windowId) ?: run {
            cancelSession()
            return
        }
        when (s) {
            is ManipulationSession.Move -> updateMove(s, ray, pose)
            is ManipulationSession.Resize -> updateResize(s, ray, pose)
            is ManipulationSession.Rotate -> updateRotate(s, px, py, pose)
        }
    }

    /**
     * Move: intersect the pointer ray with the view-aligned plane captured
     * at drag start; the window follows so the grabbed local point stays
     * under the pointer. Intuitive even when the window is yawed/pitched.
     */
    private fun updateMove(s: ManipulationSession.Move, ray: Ray3, pose: SpatialPoseRef) {
        val denom = ray.direction.dot(s.planeNormal)
        if (kotlin.math.abs(denom) < 1e-6f) return
        val t = (s.planeOrigin - ray.origin).dot(s.planeNormal) / denom
        if (t < 0f) return
        val world = ray.pointAt(t)
        // Grab offset is in window-local space; rotate by the CURRENT
        // orientation so rotating windows keep the grab point under the ray.
        val rotatedOffset = pose.orientation.rotate(s.grabLocalOffset)
        val newPosition = world - rotatedOffset
        pendingPoseMutations[s.windowId] = pose.copyPose(
            position = newPosition,
        )
    }

    /**
     * Resize: keep the top-left corner anchored; width/height track the hit
     * point in window-local meters with the start session as baseline.
     */
    private fun updateResize(s: ManipulationSession.Resize, ray: Ray3, pose: SpatialPoseRef) {
        val local = SpatialHitTest.intersectWindowPlane(ray, pose)?.localPoint ?: return
        // Clamp to a sane band so a stray ray can't collapse/inflate.
        val newW = (s.startWidth + 2f * (local.x - s.startLocal.x)).coerceIn(MIN_SIZE, MAX_SIZE)
        val newH = (s.startHeight + 2f * (local.y - s.startLocal.y)).coerceIn(MIN_SIZE, MAX_SIZE)
        pendingPoseMutations[s.windowId] = pose.copyPose(widthMeters = newW, heightMeters = newH)
    }

    /** Rotate: horizontal pointer travel maps to yaw around world +Y. */
    private fun updateRotate(s: ManipulationSession.Rotate, px: Float, py: Float, pose: SpatialPoseRef) {
        val dxPx = px - s.startPointerPx
        val dyPx = py - s.startPointerPy
        val yawDeg = dxPx * ROTATE_DEG_PER_PX
        val pitchDeg = -dyPx * ROTATE_DEG_PER_PX
        val delta = Quat.fromEulerDegrees(yawDeg = yawDeg, pitchDeg = pitchDeg)
        pendingPoseMutations[s.windowId] = pose.copyPose(
            orientation = (delta * s.startOrientation).normalize(),
        )
    }

    // ── plumbing between controller and workspace layer ──

    /**
     * Pose writes produced by sessions this frame. The workspace layer
     * drains this after dispatching intents (single consumer per frame).
     */
    val pendingPoseMutations = LinkedHashMap<Long, SpatialPoseRef>()

    private fun currentTargets(): List<SpatialSceneQuery.WindowTarget> =
        targetsProvider.invoke()

    private var targetsProvider: () -> List<SpatialSceneQuery.WindowTarget> = { emptyList() }

    /** Scene snapshot provider (window id/pose/content size), set by host. */
    fun setTargetsProvider(provider: () -> List<SpatialSceneQuery.WindowTarget>) {
        targetsProvider = provider
    }

    private fun SpatialPoseRef.copyPose(
        position: Vec3 = this.position,
        orientation: Quat = this.orientation,
        widthMeters: Float = this.widthMeters,
        heightMeters: Float = this.heightMeters,
    ): SpatialPoseRef = MutablePose(position, orientation, widthMeters, heightMeters)

    /** Simple mutable pose the controller writes during sessions. */
    data class MutablePose(
        override var position: Vec3,
        override var orientation: Quat,
        override var widthMeters: Float,
        override var heightMeters: Float,
    ) : SpatialPoseRef

    companion object {
        const val MIN_SIZE = 0.10f
        const val MAX_SIZE = 3.00f
        const val ROTATE_DEG_PER_PX = 0.10f
    }
}
