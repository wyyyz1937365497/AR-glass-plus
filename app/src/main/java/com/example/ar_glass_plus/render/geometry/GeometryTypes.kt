package com.example.ar_glass_plus.render.geometry

/**
 * Pure Kotlin geometry primitives (no android.* imports) so this package is
 * host-JVM unit-testable and backend-agnostic (GL, Vulkan, input all consume
 * the same math). Coordinate convention: origin top-left, x right, y down,
 * unit = pixels (content or output).
 */
data class PixelSize(val width: Float, val height: Float)

data class PixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class PixelPoint(val x: Float, val y: Float)
