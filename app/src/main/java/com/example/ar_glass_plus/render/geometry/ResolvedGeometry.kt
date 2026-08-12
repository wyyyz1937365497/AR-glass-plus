package com.example.ar_glass_plus.render.geometry

/**
 * The result of placing a source into one target region.
 * [sourceRect] is normalized (0..1) crop; [destinationRect] is output pixels
 * (top-left origin). [rotation] is applied when sampling.
 */
data class ResolvedGeometry(
    val sourceSize: PixelSize,
    val targetRegion: PixelRect,
    val destinationRect: PixelRect,
    val sourceRect: PixelRect,
    val rotation: ContentRotation,
)
