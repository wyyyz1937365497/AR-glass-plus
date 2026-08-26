package com.example.ar_glass_plus.render.spatial

import kotlin.math.cos
import kotlin.math.sin

/**
 * Orientation quaternion (x, y, z, w), Hamilton product convention, assumed
 * unit length (constructors here always produce unit quaternions).
 *
 * Rotation convention: for an angle θ around unit axis a the quaternion is
 * (a·sin(θ/2), cos(θ/2)) and [q]v rotates vectors in the ACTIVE sense
 * (v' = q·v·q⁻¹), matching the right-handed world in Vec3.
 */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Quat): Quat = Quat(
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w,
        w = w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** Inverse of a unit quaternion. */
    fun conjugate(): Quat = Quat(-x, -y, -z, w)

    fun normalize(): Quat {
        val n = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
        if (n == 0f || n == 1f) return this
        return Quat(x / n, y / n, z / n, w / n)
    }

    /** Active rotation of a vector: q·v·q⁻¹. */
    fun rotate(v: Vec3): Vec3 {
        val qv = Quat(v.x, v.y, v.z, 0f)
        val r = this * qv * conjugate()
        return Vec3(r.x, r.y, r.z)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /**
         * Euler angles (degrees) around the fixed WORLD axes:
         * yaw = +Y (turn right edge away), pitch = +X (top toward viewer),
         * roll = +Z (counterclockwise on screen). Composition:
         * q = yawQ · pitchQ · rollQ.
         */
        fun fromEulerDegrees(yawDeg: Float = 0f, pitchDeg: Float = 0f, rollDeg: Float = 0f): Quat {
            val y = fromAxisAngleDegrees(Vec3(0f, 1f, 0f), yawDeg)
            val p = fromAxisAngleDegrees(Vec3(1f, 0f, 0f), pitchDeg)
            val r = fromAxisAngleDegrees(Vec3(0f, 0f, 1f), rollDeg)
            return (y * p * r).normalize()
        }

        fun fromAxisAngleDegrees(axis: Vec3, angleDeg: Float): Quat {
            val len = axis.length()
            if (len == 0f) return IDENTITY
            val a = axis * (1f / len)
            val half = Math.toRadians(angleDeg.toDouble()) / 2.0
            val s = sin(half).toFloat()
            val c = cos(half).toFloat()
            return Quat(a.x * s, a.y * s, a.z * s, c)
        }
    }
}
