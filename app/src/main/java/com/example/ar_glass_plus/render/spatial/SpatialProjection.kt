package com.example.ar_glass_plus.render.spatial

import com.example.ar_glass_plus.render.geometry.PixelPoint

/**
 * CPU-side spatial scene math: model matrices for window poses, projection
 * of world points to output pixels, and content→output mapping for the
 * cursor overlay. This is the reference implementation the GL renderer must
 * match; every function here is host-JVM unit-tested.
 *
 * Window quad convention: a window is a rectangle in its local XY plane,
 * local +X right, local +Y UP (world up when unrotated), centered on
 * [SpatialPose.position]. Content coordinates are top-left origin, y down —
 * content (0,0) is the window's local top-left corner (-w/2, +h/2).
 */
object SpatialProjection {

    /** Model matrix for a window pose: T · R · S. */
    fun windowModelMatrix(pose: SpatialPoseRef): Mat4 =
        Mat4.translation(pose.position) *
            Mat4.rotation(pose.orientation) *
            Mat4.scale(pose.widthMeters, pose.heightMeters, 1f)

    /** Unit-quad corners (before model transform), TL TR BR BL. */
    val UNIT_QUAD: List<Vec3> = listOf(
        Vec3(-0.5f, 0.5f, 0f),
        Vec3(0.5f, 0.5f, 0f),
        Vec3(0.5f, -0.5f, 0f),
        Vec3(-0.5f, -0.5f, 0f),
    )

    /**
     * Projects a world point to output pixels (top-left origin, y down).
     * Null when the point is behind the camera (clip w <= 0).
     */
    fun projectToPixels(
        world: Vec3,
        viewProj: Mat4,
        outputWidth: Float,
        outputHeight: Float,
    ): PixelPoint? {
        val clip = viewProj.transform(world.x, world.y, world.z, 1f)
        if (clip[3] <= 0f) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        return PixelPoint(
            x = (ndcX * 0.5f + 0.5f) * outputWidth,
            y = (1f - (ndcY * 0.5f + 0.5f)) * outputHeight,
        )
    }

    /** World position of a window's quad corner (TL/TR/BR/BL index). */
    fun cornerWorld(pose: SpatialPoseRef, cornerIndex: Int): Vec3 {
        val local = UNIT_QUAD[cornerIndex]
        val scaled = Vec3(
            local.x * pose.widthMeters,
            local.y * pose.heightMeters,
            0f,
        )
        return pose.position + pose.orientation.rotate(scaled)
    }

    /**
     * Projects all four window corners to pixels; null if ANY corner falls
     * behind the camera (caller should skip the window this frame).
     */
    fun projectWindowCorners(
        pose: SpatialPoseRef,
        viewProj: Mat4,
        outputWidth: Float,
        outputHeight: Float,
    ): List<PixelPoint>? {
        val out = ArrayList<PixelPoint>(4)
        for (i in 0 until 4) {
            out.add(projectToPixels(cornerWorld(pose, i), viewProj, outputWidth, outputHeight) ?: return null)
        }
        return out
    }

    /**
     * Cursor mapping: content point (top-left origin, y down, unit = content
     * pixels) → output pixels on the window quad. Null when behind camera.
     */
    fun contentPointToOutputPixels(
        contentX: Float,
        contentY: Float,
        contentWidth: Float,
        contentHeight: Float,
        pose: SpatialPoseRef,
        viewProj: Mat4,
        outputWidth: Float,
        outputHeight: Float,
    ): PixelPoint? {
        if (contentWidth <= 0f || contentHeight <= 0f) return null
        val localX = (contentX / contentWidth - 0.5f) * pose.widthMeters
        val localY = (0.5f - contentY / contentHeight) * pose.heightMeters
        val world = pose.position + pose.orientation.rotate(Vec3(localX, localY, 0f))
        return projectToPixels(world, viewProj, outputWidth, outputHeight)
    }
}

/**
 * Read-side pose shape. [com.example.ar_glass_plus.workspace.SpatialPose]
 * satisfies this; GL code depends on this interface, not on workspace types
 * (render must not import the workspace layer).
 */
interface SpatialPoseRef {
    val position: Vec3
    val orientation: Quat
    val widthMeters: Float
    val heightMeters: Float
}
