package com.example.ar_glass_plus.render.spatial

/**
 * Orientation quaternion (x, y, z, w), Hamilton convention. Pure data —
 * rotation math arrives with P4.7C spatial-scene math that consumes it.
 */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)
    }
}
