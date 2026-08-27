package com.example.ar_glass_plus.interaction.spatial

import com.example.ar_glass_plus.render.spatial.Vec3

/**
 * A ray in world space: p(t) = origin + t·direction, t ≥ 0, direction
 * normalized. Pure data — the unprojection that produces one lives in
 * [SpatialHitTest].
 */
data class Ray3(
    val origin: Vec3,
    val direction: Vec3,
) {
    fun pointAt(t: Float): Vec3 = origin + direction * t
}
