package com.example.ar_glass_plus.render.geometry

/**
 * Forward (content → output) and inverse (output → content) mapping sharing
 * one ResolvedGeometry. Returns null when the point is outside the rendered
 * content (letterbox bars / crop margin) — callers must not inject there.
 */
object GeometryMapper {

    /** Content pixel → output pixel. Null if outside the sampled region. */
    fun mapContentToOutput(point: PixelPoint, g: ResolvedGeometry): PixelPoint? {
        val nx = (point.x / g.sourceSize.width - g.sourceRect.left) / g.sourceRect.width
        val ny = (point.y / g.sourceSize.height - g.sourceRect.top) / g.sourceRect.height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        val (dx, dy) = rotateForward(nx, ny, g.rotation)
        return PixelPoint(
            g.destinationRect.left + dx * g.destinationRect.width,
            g.destinationRect.top + dy * g.destinationRect.height,
        )
    }

    /** Output pixel → content pixel. Null in letterbox bars / outside crop. */
    fun mapOutputToContent(point: PixelPoint, g: ResolvedGeometry): PixelPoint? {
        val dx = (point.x - g.destinationRect.left) / g.destinationRect.width
        val dy = (point.y - g.destinationRect.top) / g.destinationRect.height
        if (dx < 0f || dx > 1f || dy < 0f || dy > 1f) return null
        val (nx, ny) = rotateInverse(dx, dy, g.rotation)
        val cx = (g.sourceRect.left + nx * g.sourceRect.width) * g.sourceSize.width
        val cy = (g.sourceRect.top + ny * g.sourceRect.height) * g.sourceSize.height
        if (cx < 0f || cx >= g.sourceSize.width || cy < 0f || cy >= g.sourceSize.height) return null
        return PixelPoint(cx, cy)
    }

    // Rotation must match the GL shader forward transform exactly:
    //   DEG_90: (x, y) -> (y, 1 - x); DEG_180: (1-x, 1-y); DEG_270: (1-y, x)
    private fun rotateForward(x: Float, y: Float, r: ContentRotation): Pair<Float, Float> =
        when (r) {
            ContentRotation.DEG_0 -> x to y
            ContentRotation.DEG_90 -> y to (1f - x)
            ContentRotation.DEG_180 -> (1f - x) to (1f - y)
            ContentRotation.DEG_270 -> (1f - y) to x
        }

    private fun rotateInverse(x: Float, y: Float, r: ContentRotation): Pair<Float, Float> =
        when (r) {
            ContentRotation.DEG_0 -> x to y
            ContentRotation.DEG_90 -> (1f - y) to x
            ContentRotation.DEG_180 -> (1f - x) to (1f - y)
            ContentRotation.DEG_270 -> y to (1f - x)
        }
}
