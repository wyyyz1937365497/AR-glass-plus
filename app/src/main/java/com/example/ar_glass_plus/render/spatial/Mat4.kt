package com.example.ar_glass_plus.render.spatial

/**
 * 4x4 column-major matrix (GL layout: m[column * 4 + row]). Pure Kotlin —
 * the single source of projection/view math shared by the GL renderer and
 * the host-JVM CPU reference tests. No android.* imports.
 */
class Mat4 private constructor(val m: FloatArray) {

    /** m[column][row]: e.g. this[0,3] is translation x. */
    operator fun get(col: Int, row: Int): Float = m[col * 4 + row]

    operator fun times(o: Mat4): Mat4 = multiply(this, o)

    /** Transform a point (w=1) / direction (w=0); returns clip-space xyzw. */
    fun transform(x: Float, y: Float, z: Float, w: Float): FloatArray {
        return floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12] * w,
            m[1] * x + m[5] * y + m[9] * z + m[13] * w,
            m[2] * x + m[6] * y + m[10] * z + m[14] * w,
            m[3] * x + m[7] * y + m[11] * z + m[15] * w,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is Mat4 && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    companion object {
        fun identity(): Mat4 = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** GL-style right-handed perspective; camera looks down -Z. */
        fun perspective(fovyDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / kotlin.math.tan(Math.toRadians(fovyDegrees.toDouble()) / 2.0).toFloat()
            val nf = 1f / (near - far)
            return Mat4(
                floatArrayOf(
                    f / aspect, 0f, 0f, 0f,
                    0f, f, 0f, 0f,
                    0f, 0f, (far + near) * nf, -1f,
                    0f, 0f, 2f * far * near * nf, 0f,
                ),
            )
        }

        /**
         * Perspective with principal-point (optical center) offset, in NDC
         * units, for calibration: [principalOffsetXNdc] > 0 shifts the
         * projected image content LEFT on screen (the principal point moves
         * right of the viewport center). Zero offsets == [perspective].
         */
        fun perspectiveWithPrincipalOffset(
            fovyDegrees: Float,
            aspect: Float,
            near: Float,
            far: Float,
            principalOffsetXNdc: Float = 0f,
            principalOffsetYNdc: Float = 0f,
        ): Mat4 {
            val base = perspective(fovyDegrees, aspect, near, far)
            val m = base.m.copyOf()
            // x_ndc = x_clip / w_clip; a constant NDC shift c requires
            // x_clip += c * w_clip, and w_clip = -z (m[11] == -1), so the
            // shift lands in column 2 (the z column): m[8] -= c.
            m[8] -= principalOffsetXNdc
            m[9] += principalOffsetYNdc // NDC +y is up; screen +y is down.
            return Mat4(m)
        }

        /**
         * General 4x4 inverse (cofactor method). Returns null when singular
         * (determinant ~ 0) so callers can skip degenerate frames instead of
         * rendering NaNs.
         */
        fun inverse(a: Mat4): Mat4? {
            val m = a.m
            val inv = FloatArray(16)

            inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
            inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
            inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
            inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
            inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
            inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
            inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
            inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
            inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
            inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
            inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
            inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
            inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
            inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
            inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
            inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

            val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
            if (kotlin.math.abs(det) < 1e-12f) return null
            val invDet = 1f / det
            for (i in 0 until 16) inv[i] *= invDet
            return Mat4(inv)
        }

        fun translation(t: Vec3): Mat4 = translation(t.x, t.y, t.z)

        fun translation(x: Float, y: Float, z: Float): Mat4 = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                x, y, z, 1f,
            ),
        )

        fun scale(x: Float, y: Float, z: Float): Mat4 = Mat4(
            floatArrayOf(
                x, 0f, 0f, 0f,
                0f, y, 0f, 0f,
                0f, 0f, z, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** Rotation matrix from a unit quaternion. */
        fun rotation(q: Quat): Mat4 {
            val x = q.x
            val y = q.y
            val z = q.z
            val w = q.w
            return Mat4(
                floatArrayOf(
                    1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y), 0f,
                    2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x), 0f,
                    2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y), 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        fun multiply(a: Mat4, b: Mat4): Mat4 {
            val r = FloatArray(16)
            for (col in 0 until 4) {
                for (row in 0 until 4) {
                    r[col * 4 + row] =
                        a.m[row] * b.m[col * 4] +
                            a.m[4 + row] * b.m[col * 4 + 1] +
                            a.m[8 + row] * b.m[col * 4 + 2] +
                            a.m[12 + row] * b.m[col * 4 + 3]
                }
            }
            return Mat4(r)
        }

        /**
         * View matrix (world→camera) for a camera with [position] and
         * [orientation]: view = R(q)ᵀ · T(-position).
         */
        fun view(position: Vec3, orientation: Quat): Mat4 =
            rotation(orientation.conjugate()) * translation(-position)
    }
}
