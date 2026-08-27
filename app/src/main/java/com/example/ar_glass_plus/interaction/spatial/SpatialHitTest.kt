package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.SpatialPoseRef
import com.example.ar_glass_plus.render.spatial.Vec3

/**
 * CPU ray/geometry hit testing for the spatial workspace — the inverse of
 * the render projection, sharing its math (Mat4, SpatialProjection
 * conventions). No GL, no Android types.
 *
 * Coordinate conventions (identical to render/spatial):
 *  - world: +X right, +Y up, -Z forward; window quads centered on pose
 *    position, local +X right / +Y up in their own plane.
 *  - output pixels: top-left origin, y down, [0..width)x[0..height).
 */
object SpatialHitTest {

    /**
     * Unproject a screen pixel into a world ray through the given camera.
     * [invViewProjection] = inverse(projection · view) precomputed by the
     * caller (it is constant per frame/eye). Null when the inverse is
     * singular.
     */
    fun rayFromPixel(
        px: Float,
        py: Float,
        width: Float,
        height: Float,
        camera: SpatialCamera,
        invViewProjection: Mat4?,
    ): Ray3? {
        if (width <= 0f || height <= 0f) return null
        val inv = invViewProjection ?: return null
        // Pixel -> NDC (y flips because screen y is down).
        val ndcX = (px / width) * 2f - 1f
        val ndcY = 1f - (py / height) * 2f
        // Near/far points through the frustum corners.
        val near = inv.transform(ndcX, ndcY, -1f, 1f)
        val far = inv.transform(ndcX, ndcY, 1f, 1f)
        if (near[3] == 0f || far[3] == 0f) return null
        val origin = Vec3(near[0] / near[3], near[1] / near[3], near[2] / near[3])
        val farPoint = Vec3(far[0] / far[3], far[1] / far[3], far[2] / far[3])
        val dir = farPoint - origin
        val len = dir.length()
        if (len < 1e-9f) return null
        return Ray3(origin, dir * (1f / len))
    }

    /** Convenience: builds the inverse from projection·view. */
    fun rayFromPixelWithProjection(
        px: Float,
        py: Float,
        width: Float,
        height: Float,
        camera: SpatialCamera,
        viewProjection: Mat4,
    ): Ray3? = rayFromPixel(px, py, width, height, camera, Mat4.inverse(viewProjection))

    /**
     * Intersect a ray with a window's (infinite) plane and express the hit
     * in window-local coordinates: x ∈ [-w/2, w/2] right, y ∈ [-h/2, h/2]
     * up. Null when the ray is parallel to the plane or hits it behind the
     * camera.
     */
    fun intersectWindowPlane(ray: Ray3, pose: SpatialPoseRef): LocalHit? {
        val normal = pose.orientation.rotate(Vec3(0f, 0f, 1f))
        val denom = ray.direction.dot(normal)
        if (kotlin.math.abs(denom) < 1e-6f) return null
        val toPlane = pose.position - ray.origin
        val t = toPlane.dot(normal) / denom
        if (t < 0f) return null
        val world = ray.pointAt(t)
        val local = pose.orientation.conjugate().rotate(world - pose.position)
        return LocalHit(t, world, Vec3(local.x, local.y, 0f))
    }

    data class LocalHit(
        val t: Float,
        val worldPoint: Vec3,
        /** Window-plane point; z ≈ 0, x/y in meters around the center. */
        val localPoint: Vec3,
    )

    /** Content UV [0,1]² from window-local meters; null outside the quad. */
    fun localToUv(local: Vec3, pose: SpatialPoseRef): Pair<Float, Float>? {
        val u = local.x / pose.widthMeters + 0.5f
        val v = 0.5f - local.y / pose.heightMeters
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        return u to v
    }

    /** UV → content pixels of this window's VirtualDisplay. */
    fun uvToContentPixels(u: Float, v: Float, contentWidth: Int, contentHeight: Int): Pair<Float, Float> =
        (u * (contentWidth - 1)) to (v * (contentHeight - 1))

    /** Full chain: ray → window-local → content pixels; null on miss. */
    fun rayToContentPixels(ray: Ray3, pose: SpatialPoseRef, contentWidth: Int, contentHeight: Int): Pair<Float, Float>? {
        val hit = intersectWindowPlane(ray, pose) ?: return null
        val (u, v) = localToUv(hit.localPoint, pose) ?: return null
        return uvToContentPixels(u, v, contentWidth, contentHeight)
    }
}
