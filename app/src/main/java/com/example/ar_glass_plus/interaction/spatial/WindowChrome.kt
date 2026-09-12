package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.SpatialPoseRef
import com.example.ar_glass_plus.render.spatial.Vec3

/**
 * Decorations drawn by the scene renderer AROUND the Android content quad:
 * a title bar on top and a resize handle at the bottom-right corner.
 * Content occupies the inner rect; hit-testing classifies which region a
 * window-local point lands in.
 *
 * Local layout (meters, +x right, +y up, origin = window center):
 *
 *        ┌──────────────────────────┐ ─ top edge = +h/2 + titleBar
 *        │        TitleBar          │
 *        ├──────────────────────────┤ ─ content top = +h/2
 *        │                          │
 *        │   Content (Android VD)   │
 *        │                          │
 *        │                    ▪ RH  │ ─ handle square at (+w/2, -h/2)
 *        └──────────────────────────┘ ─ bottom = -h/2
 */
object WindowChrome {

    /** Title bar height in meters above the content quad. */
    const val TITLE_BAR_METERS = 0.03f

    /** Resize handle square side in meters (anchored at content BR corner). */
    const val RESIZE_HANDLE_METERS = 0.045f

    /** Border strip thickness treated as a distinct hit region. */
    const val BORDER_METERS = 0.008f

    enum class Region {
        TITLE_BAR,
        CONTENT,
        RESIZE_HANDLE,
        BORDER,
        OUTSIDE,
    }

    /**
     * Total decorated bounds (content + title bar) in window-local meters.
     * x ∈ [-w/2, w/2], y ∈ [-h/2, h/2 + titleBar].
     */
    fun decoratedLocalBounds(pose: SpatialPoseRef): Pair<Vec3, Vec3> =
        Vec3(-pose.widthMeters / 2f, -pose.heightMeters / 2f, 0f) to
            Vec3(pose.widthMeters / 2f, pose.heightMeters / 2f + TITLE_BAR_METERS, 0f)

    /** Classify a window-local point (meters). */
    fun regionAt(local: Vec3, pose: SpatialPoseRef): Region {
        val halfW = pose.widthMeters / 2f
        val halfH = pose.heightMeters / 2f
        val titleTop = halfH + TITLE_BAR_METERS
        val handle = RESIZE_HANDLE_METERS
        val border = BORDER_METERS

        val x = local.x
        val y = local.y

        // Outside the decorated rect entirely?
        if (x < -halfW || x > halfW || y < -halfH || y > titleTop) return Region.OUTSIDE

        // Title bar: between content top and decorated top.
        if (y > halfH) return Region.TITLE_BAR

        // Resize handle: square overlapping the content bottom-right corner.
        if (x >= halfW - handle && y <= -halfH + handle) return Region.RESIZE_HANDLE

        // Thin border ring inside the content rect.
        if (x <= -halfW + border || x >= halfW - border || y >= halfH - border || y <= -halfH + border) {
            return Region.BORDER
        }

        return Region.CONTENT
    }
}

/** Hit result against one window, including the chrome classification. */
sealed interface SpatialHit {
    val windowId: Long
    val localPoint: Vec3
    val distance: Float

    /** Android content area — [contentX]/[contentY] in VD pixels. */
    data class Content(
        override val windowId: Long,
        override val localPoint: Vec3,
        override val distance: Float,
        val contentX: Float,
        val contentY: Float,
    ) : SpatialHit

    data class TitleBar(override val windowId: Long, override val localPoint: Vec3, override val distance: Float) : SpatialHit
    data class ResizeHandle(override val windowId: Long, override val localPoint: Vec3, override val distance: Float) : SpatialHit
    data class Border(override val windowId: Long, override val localPoint: Vec3, override val distance: Float) : SpatialHit
}

/**
 * Scene-level query: nearest-front window hit for a pointer ray. Windows
 * are provided as (id, pose, contentSize) in any order; overlapping windows
 * resolve by smallest positive ray distance.
 */
object SpatialSceneQuery {

    data class WindowTarget(
        val id: Long,
        val pose: SpatialPoseRef,
        val contentWidth: Int,
        val contentHeight: Int,
    )

    fun hitTest(
        ray: Ray3,
        windows: List<WindowTarget>,
        preferredWindowId: Long? = null,
    ): SpatialHit? {
        var best: SpatialHit? = null
        for (w in windows) {
            val local = SpatialHitTest.intersectWindowPlane(ray, w.pose) ?: continue
            val region = WindowChrome.regionAt(local.localPoint, w.pose)
            if (region == WindowChrome.Region.OUTSIDE) continue
            val hit: SpatialHit = when (region) {
                WindowChrome.Region.CONTENT -> {
                    val (u, v) = SpatialHitTest.localToUv(local.localPoint, w.pose) ?: continue
                    val (cx, cy) = SpatialHitTest.uvToContentPixels(u, v, w.contentWidth, w.contentHeight)
                    SpatialHit.Content(w.id, local.localPoint, local.t, cx, cy)
                }
                WindowChrome.Region.TITLE_BAR -> SpatialHit.TitleBar(w.id, local.localPoint, local.t)
                WindowChrome.Region.RESIZE_HANDLE -> SpatialHit.ResizeHandle(w.id, local.localPoint, local.t)
                WindowChrome.Region.BORDER -> SpatialHit.Border(w.id, local.localPoint, local.t)
                WindowChrome.Region.OUTSIDE -> continue
            }
            val current = best
            val distanceDifference = if (current == null) 0f else hit.distance - current.distance
            if (
                current == null ||
                distanceDifference < -HIT_DISTANCE_EPSILON ||
                (
                    kotlin.math.abs(distanceDifference) <= HIT_DISTANCE_EPSILON &&
                        hit.windowId == preferredWindowId &&
                        current.windowId != preferredWindowId
                )
            ) {
                best = hit
            }
        }
        return best
    }

    private const val HIT_DISTANCE_EPSILON = 0.0001f

    /** Convenience overload building the ray from a screen pixel. */
    fun hitTestPixel(
        px: Float,
        py: Float,
        width: Float,
        height: Float,
        camera: SpatialCamera,
        viewProjection: Mat4,
        windows: List<WindowTarget>,
    ): SpatialHit? {
        val ray = SpatialHitTest.rayFromPixelWithProjection(px, py, width, height, camera, viewProjection) ?: return null
        return hitTest(ray, windows)
    }
}
