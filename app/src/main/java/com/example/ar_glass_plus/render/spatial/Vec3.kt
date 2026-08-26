package com.example.ar_glass_plus.render.spatial

/**
 * Position/vector in world space, unit = meters. Pure data — arithmetic
 * arrives with P4.7C spatial-scene math that actually consumes it.
 */
data class Vec3(val x: Float, val y: Float, val z: Float)
