package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorState
import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.Vec3

/**
 * Stable identity of a spatial window. Opaque Long — never interpret the
 * value; only compare for equality.
 */
@JvmInline
value class SpatialWindowId(val value: Long)

enum class WindowLifecycle {
    /** Window exists in the scene; its VirtualDisplay is being created. */
    CREATING,

    /** Content VD is running; app launch pending or failed. */
    CONTENT_READY,

    /** App launched onto this window's content display. */
    RUNNING,
}

/**
 * World-space placement of a window. Consumed by the P4.7C spatial scene
 * (continuous 3D workspace); the Gate-1 tile compositor does NOT read it.
 * VirtualDisplay resolution (content quality) is fully decoupled from these
 * meters — a window never resizes its VD.
 */
data class SpatialPose(
    val position: Vec3 = Vec3(0f, 0f, -1.2f),
    val orientation: Quat = Quat.IDENTITY,
    val widthMeters: Float = 0.8f,
    val heightMeters: Float = 0.45f,
)

data class SpatialWindowState(
    val id: SpatialWindowId,
    val app: ActiveApp,
    /** Tile slot for the smoke-test compositor: 0=TL 1=TR 2=BL 3=BR. */
    val slot: Int,
    val lifecycle: WindowLifecycle = WindowLifecycle.CREATING,
    val contentDisplayId: Int? = null,
    val content: ContentSpec? = null,
    val cursor: CursorState? = null,
    val pose: SpatialPose = SpatialPose(),
)

data class SpatialSceneState(
    /** Insertion-ordered (creation order); stable for UI diffing. */
    val windows: Map<SpatialWindowId, SpatialWindowState> = emptyMap(),
    /** Single source of truth for focus — no per-window focused flag. */
    val focusedWindowId: SpatialWindowId? = null,
)

object SpatialWindowModel {
    const val MAX_WINDOWS = 4
    const val TILE_COLUMNS = 2
    const val TILE_ROWS = 2

    fun slotLabel(slot: Int): String = when (slot) {
        0 -> "TL"
        1 -> "TR"
        2 -> "BL"
        3 -> "BR"
        else -> "S$slot"
    }
}
