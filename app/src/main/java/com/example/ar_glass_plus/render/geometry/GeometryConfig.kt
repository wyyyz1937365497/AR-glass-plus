package com.example.ar_glass_plus.render.geometry

/**
 * Runtime-tunable geometry for the content → output placement. Changing this
 * re-resolves next frame; nothing (VD/OES/app) is rebuilt.
 */
data class GeometryConfig(
    val aspectMode: AspectMode = AspectMode.FIT,
    val rotation: ContentRotation = ContentRotation.DEG_0,
)
