package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.api.PointerAction
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

    /** Android content drag; pointer moves inject absolute content pixels. */
    CONTENT_DRAG,
}

/** State of the spatial pointer, consumed by UI + renderer (cursor ring). */
data class SpatialPointerState(
    val mode: SpatialPointerMode = SpatialPointerMode.HOVER,
    /** Window under the pointer, if any (hover focus candidate). */
    val hoveredWindowId: Long? = null,
    /** Window being manipulated, if any. */
    val activeWindowId: Long? = null,
    /** Current hit in window-local meters, used to draw the spatial cursor. */
    val localPoint: Vec3? = null,
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
        val action: PointerAction,
        val button: MouseButton,
    ) : SpatialIntent

    /** Scroll the content app under the ray; deltas remain raw finger pixels. */
    data class ScrollContent(
        val windowId: Long,
        val dx: Float,
        val dy: Float,
    ) : SpatialIntent

    /** Commit one manipulation update to the workspace scene. */
    data class UpdatePose(
        val windowId: Long,
        val pose: SpatialPoseRef,
    ) : SpatialIntent

}

/** A window manipulation in progress. */
internal sealed interface ManipulationSession {
    val windowId: Long

    data class Move(
        override val windowId: Long,
        /** View-aligned plane the drag lives on (normal = camera forward). */
        val planeOrigin: Vec3,
        val planeNormal: Vec3,
        val grabLocalOffset: Vec3,
    ) : ManipulationSession

    data class Resize(
        override val windowId: Long,
        val startPosition: Vec3,
        val startOrientation: Quat,
        val planeNormal: Vec3,
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
    private val readFocusedWindowId: () -> Long?,
) {

    private val _state = MutableStateFlow(SpatialPointerState())
    val state: StateFlow<SpatialPointerState> = _state.asStateFlow()

    private var session: ManipulationSession? = null
    @Volatile
    private var frameParams: SpatialFrameParams? = null
    private var pendingContentDrag: PendingContentDrag? = null

    private data class PendingContentDrag(
        val hit: SpatialHit.Content,
        val button: MouseButton,
    )

    // ── frame plumbing ──

    /** Call each frame (renderer) BEFORE pointer events of that frame. */
    fun onFrame(params: SpatialFrameParams) {
        frameParams = params
    }

    fun onFrameUnavailable() {
        frameParams = null
        cancelSession()
    }

    fun currentOutputSize(): Pair<Float, Float>? =
        frameParams?.let { it.outputWidthPx to it.outputHeightPx }

    // ── pointer events (screen pixels, top-left origin) ──

    fun onPointerMove(px: Float, py: Float) {
        val params = frameParams ?: return
        val ray = SpatialHitTest.rayFromPixelWithProjection(px, py, params.outputWidthPx, params.outputHeightPx, params.camera, params.viewProjection) ?: return

        val s = session
        if (s != null) {
            updateSession(s, ray, px, py)
            return
        }

        val drag = pendingContentDrag
        if (drag != null) {
            val moved = contentHitForWindow(ray, drag.hit.windowId) ?: return
            pendingContentDrag = drag.copy(hit = moved)
            _state.value = _state.value.copy(
                hoveredWindowId = moved.windowId,
                activeWindowId = moved.windowId,
                localPoint = moved.localPoint,
            )
            emitIntent(
                SpatialIntent.InjectContent(
                    moved.windowId,
                    moved.contentX,
                    moved.contentY,
                    PointerAction.MOVE,
                    drag.button,
                ),
            )
            return
        }

        val hit = SpatialSceneQuery.hitTest(ray, currentTargets(), readFocusedWindowId())
        if (hit == null) {
            if (_state.value.hoveredWindowId != null) {
                _state.value = _state.value.copy(hoveredWindowId = null, localPoint = null)
            }
            return
        }
        _state.value = _state.value.copy(
            hoveredWindowId = hit.windowId,
            localPoint = hit.localPoint,
        )
    }

    fun onPointerDown(px: Float, py: Float, button: MouseButton = MouseButton.LEFT) {
        val params = frameParams ?: return
        val ray = SpatialHitTest.rayFromPixelWithProjection(
            px,
            py,
            params.outputWidthPx,
            params.outputHeightPx,
            params.camera,
            params.viewProjection,
        ) ?: return
        val hit = SpatialSceneQuery.hitTest(ray, currentTargets(), readFocusedWindowId()) ?: return
        if (readFocusedWindowId() != hit.windowId) {
            emitIntent(SpatialIntent.Focus(hit.windowId))
            return
        }
        if (button != MouseButton.LEFT && hit !is SpatialHit.Content) return

        when (hit) {
            is SpatialHit.Content -> {
                pendingContentDrag = PendingContentDrag(hit, button)
                _state.value = _state.value.copy(
                    mode = SpatialPointerMode.CONTENT_DRAG,
                    hoveredWindowId = hit.windowId,
                    activeWindowId = hit.windowId,
                    localPoint = hit.localPoint,
                )
                emitIntent(
                    SpatialIntent.InjectContent(
                        hit.windowId,
                        hit.contentX,
                        hit.contentY,
                        PointerAction.DOWN,
                        button,
                    ),
                )
            }
            is SpatialHit.TitleBar -> {
                val pose = readPose(hit.windowId) ?: return
                session = ManipulationSession.Move(
                    windowId = hit.windowId,
                    planeOrigin = pose.position,
                    planeNormal = params.camera.orientation.rotate(Vec3(0f, 0f, -1f)),
                    grabLocalOffset = hit.localPoint,
                )
                beginManipulation(hit)
            }
            is SpatialHit.ResizeHandle -> {
                val pose = readPose(hit.windowId) ?: return
                session = ManipulationSession.Resize(
                    windowId = hit.windowId,
                    startPosition = pose.position,
                    startOrientation = pose.orientation,
                    planeNormal = pose.orientation.rotate(Vec3(0f, 0f, 1f)),
                    startWidth = pose.widthMeters,
                    startHeight = pose.heightMeters,
                    startLocal = hit.localPoint,
                )
                beginManipulation(hit)
            }
            is SpatialHit.Border -> {
                val pose = readPose(hit.windowId) ?: return
                session = ManipulationSession.Rotate(
                    windowId = hit.windowId,
                    startOrientation = pose.orientation,
                    startPointerPx = px,
                    startPointerPy = py,
                )
                beginManipulation(hit)
            }
        }
    }

    fun onClick(
        px: Float,
        py: Float,
        button: MouseButton = MouseButton.LEFT,
        clickCount: Int = 1,
    ) {
        val hit = hitAt(px, py) ?: return
        if (hit !is SpatialHit.Content) {
            emitIntent(SpatialIntent.Focus(hit.windowId))
            return
        }
        _state.value = _state.value.copy(
            hoveredWindowId = hit.windowId,
            localPoint = hit.localPoint,
        )
        emitIntent(
            SpatialIntent.InjectContent(
                hit.windowId,
                hit.contentX,
                hit.contentY,
                if (clickCount == 2) PointerAction.DOUBLE_CLICK else PointerAction.CLICK,
                button,
            ),
        )
    }

    fun onScroll(px: Float, py: Float, dx: Float, dy: Float) {
        val hit = hitAt(px, py) as? SpatialHit.Content ?: return
        if (readFocusedWindowId() != hit.windowId) {
            emitIntent(SpatialIntent.Focus(hit.windowId))
            return
        }
        emitIntent(SpatialIntent.ScrollContent(hit.windowId, dx, dy))
    }

    fun onPointerUp() {
        val drag = pendingContentDrag
        if (drag != null) {
            pendingContentDrag = null
            emitIntent(
                SpatialIntent.InjectContent(
                    drag.hit.windowId,
                    drag.hit.contentX,
                    drag.hit.contentY,
                    PointerAction.UP,
                    drag.button,
                ),
            )
        }
        if (session != null) session = null
        _state.value = _state.value.copy(
            mode = SpatialPointerMode.HOVER,
            activeWindowId = null,
        )
    }

    /** Cancel any session without emitting UP; teardown releases backend buttons. */
    fun cancelSession() {
        session = null
        pendingContentDrag = null
        _state.value = SpatialPointerState()
    }

    // ── session updates ──

    private fun updateSession(s: ManipulationSession, ray: Ray3, px: Float, py: Float) {
        val pose = readPose(s.windowId) ?: run {
            cancelSession()
            return
        }
        _state.value = _state.value.copy(
            localPoint = SpatialHitTest.intersectWindowPlane(ray, pose)?.localPoint,
        )
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
        emitIntent(
            SpatialIntent.UpdatePose(
                s.windowId,
                pose.copyPose(position = newPosition),
            ),
        )
    }

    /**
     * Resize: move the bottom-right corner on the starting window plane while
     * the content top-left corner remains fixed in world space.
     */
    private fun updateResize(s: ManipulationSession.Resize, ray: Ray3, pose: SpatialPoseRef) {
        val denominator = ray.direction.dot(s.planeNormal)
        if (kotlin.math.abs(denominator) < 1e-6f) return
        val distance = (s.startPosition - ray.origin).dot(s.planeNormal) / denominator
        if (distance < 0f) return
        val local = s.startOrientation.conjugate().rotate(ray.pointAt(distance) - s.startPosition)
        val width = (s.startWidth + local.x - s.startLocal.x).coerceIn(MIN_SIZE, MAX_SIZE)
        val height = (s.startHeight - local.y + s.startLocal.y).coerceIn(MIN_SIZE, MAX_SIZE)
        val effectiveDeltaX = width - s.startWidth
        val effectiveDeltaY = s.startHeight - height
        val centerOffset = s.startOrientation.rotate(
            Vec3(effectiveDeltaX * 0.5f, effectiveDeltaY * 0.5f, 0f),
        )
        emitIntent(
            SpatialIntent.UpdatePose(
                s.windowId,
                pose.copyPose(
                    position = s.startPosition + centerOffset,
                    orientation = s.startOrientation,
                    widthMeters = width,
                    heightMeters = height,
                ),
            ),
        )
    }

    /** Rotate: horizontal pointer travel maps to yaw around world +Y. */
    private fun updateRotate(s: ManipulationSession.Rotate, px: Float, py: Float, pose: SpatialPoseRef) {
        val dxPx = px - s.startPointerPx
        val dyPx = py - s.startPointerPy
        val yawDeg = dxPx * ROTATE_DEG_PER_PX
        val pitchDeg = -dyPx * ROTATE_DEG_PER_PX
        val delta = Quat.fromEulerDegrees(yawDeg = yawDeg, pitchDeg = pitchDeg)
        emitIntent(
            SpatialIntent.UpdatePose(
                s.windowId,
                pose.copyPose(orientation = (delta * s.startOrientation).normalize()),
            ),
        )
    }

    // ── plumbing between controller and workspace layer ──

    private fun beginManipulation(hit: SpatialHit) {
        _state.value = _state.value.copy(
            mode = SpatialPointerMode.MANIPULATING,
            hoveredWindowId = hit.windowId,
            activeWindowId = hit.windowId,
            localPoint = hit.localPoint,
        )
    }

    private fun hitAt(px: Float, py: Float): SpatialHit? {
        val params = frameParams ?: return null
        val ray = SpatialHitTest.rayFromPixelWithProjection(
            px,
            py,
            params.outputWidthPx,
            params.outputHeightPx,
            params.camera,
            params.viewProjection,
        ) ?: return null
        return SpatialSceneQuery.hitTest(ray, currentTargets(), readFocusedWindowId())
    }

    private fun contentHitForWindow(ray: Ray3, windowId: Long): SpatialHit.Content? {
        val target = currentTargets().firstOrNull { it.id == windowId } ?: return null
        val intersection = SpatialHitTest.intersectWindowPlane(ray, target.pose) ?: return null
        val pose = target.pose
        val u = (intersection.localPoint.x / pose.widthMeters + 0.5f).coerceIn(0f, 1f)
        val v = (0.5f - intersection.localPoint.y / pose.heightMeters).coerceIn(0f, 1f)
        val (contentX, contentY) = SpatialHitTest.uvToContentPixels(
            u,
            v,
            target.contentWidth,
            target.contentHeight,
        )
        return SpatialHit.Content(
            windowId,
            intersection.localPoint,
            intersection.t,
            contentX,
            contentY,
        )
    }

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
