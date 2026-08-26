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
