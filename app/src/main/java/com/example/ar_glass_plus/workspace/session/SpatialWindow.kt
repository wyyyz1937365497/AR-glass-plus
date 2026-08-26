package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.CursorState
import com.example.ar_glass_plus.render.spatial.SpatialPoseRef
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
data class SpatialPose(
    override val position: Vec3 = Vec3(0f, 0f, -1.2f),
    override val orientation: Quat = Quat.IDENTITY,
    override val widthMeters: Float = 0.8f,
    override val heightMeters: Float = 0.45f,
) : SpatialPoseRef


data class SpatialWindowState(
    val id: SpatialWindowId,
    val app: ActiveApp,
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

    /**
     * Gate 2 fixed test scene (static head at origin): near-left window
     * yawed toward the viewer, near-right yawed the other way, plus two
     * straight windows stepping away in Z. Assignment is by creation order.
     */
    val PRESET_POSES: List<SpatialPose> = listOf(
        SpatialPose(
            position = Vec3(-0.55f, 0.18f, -1.20f),
            orientation = Quat.fromEulerDegrees(yawDeg = 20f),
            widthMeters = 0.70f,
            heightMeters = 0.394f,
        ),
        SpatialPose(
            position = Vec3(0.50f, 0.12f, -1.00f),
            orientation = Quat.fromEulerDegrees(yawDeg = -20f),
            widthMeters = 0.70f,
            heightMeters = 0.394f,
        ),
        SpatialPose(
            position = Vec3(-0.30f, -0.32f, -1.60f),
            widthMeters = 0.90f,
            heightMeters = 0.506f,
        ),
        SpatialPose(
            position = Vec3(0.40f, -0.30f, -2.00f),
            widthMeters = 1.10f,
            heightMeters = 0.619f,
        ),
    )

    fun presetPose(index: Int): SpatialPose =
        PRESET_POSES[index.coerceIn(0, PRESET_POSES.size - 1)]
}
