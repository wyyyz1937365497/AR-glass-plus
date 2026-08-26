package com.example.ar_glass_plus.render.spatial

/**
 * Position/vector in world space, unit = meters.
 *
 * World convention (right-handed):
 *  - +X right, +Y up, -Z forward (away from the viewer);
 *  - viewer/camera at origin looks down -Z with identity orientation.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun length(): Float = kotlin.math.sqrt(dot(this))

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}
