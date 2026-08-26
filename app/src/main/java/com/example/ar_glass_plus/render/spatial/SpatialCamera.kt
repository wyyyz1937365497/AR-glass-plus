package com.example.ar_glass_plus.render.spatial

/**
 * A pinhole camera: [position] + [orientation] in world space. With identity
 * orientation the camera looks down -Z, +Y up, +X right (see Vec3 for the
 * world convention). Pure data + derivation — no GL.
 */
data class SpatialCamera(
    val position: Vec3 = Vec3.ZERO,
    val orientation: Quat = Quat.IDENTITY,
) {
    /** World→camera view matrix. */
    fun viewMatrix(): Mat4 = Mat4.view(position, orientation)

    companion object {
        /** Static head for Gate 2: seated at the origin, looking forward. */
        val STATIC_HEAD = SpatialCamera()

        const val DEFAULT_FOV_Y_DEGREES = 60f
        const val DEFAULT_NEAR_METERS = 0.05f
        const val DEFAULT_FAR_METERS = 20f

        /** Typical adult interpupillary distance. */
        const val DEFAULT_IPD_METERS = 0.063f
    }
}

/**
 * Parallel-axis stereo rig derived from one head pose: both eyes share the
 * head orientation; positions offset by ±IPD/2 along the head's right axis.
 * No toe-in (parallel cameras) so the disparity math stays the simple
 * pinhole relation d = fₓ·IPD/Z — the property Gate 2's CPU reference tests
 * assert.
 */
data class StereoCamera(
    val head: SpatialCamera = SpatialCamera.STATIC_HEAD,
    val ipdMeters: Float = SpatialCamera.DEFAULT_IPD_METERS,
) {
    val leftEye: SpatialCamera
    get() {
        val right = head.orientation.rotate(Vec3(1f, 0f, 0f))
        return head.copy(position = head.position - right * (ipdMeters / 2f))
    }

    val rightEye: SpatialCamera
        get() {
            val right = head.orientation.rotate(Vec3(1f, 0f, 0f))
            return head.copy(position = head.position + right * (ipdMeters / 2f))
        }
}
