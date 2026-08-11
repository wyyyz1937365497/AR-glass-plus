package com.example.ar_glass_plus.source

/**
 * Content VirtualDisplay size. Deliberately decoupled from the physical
 * output display (RayNeo 1920x1080) — the pipeline scales, the two never
 * have to match.
 */
data class VirtualDisplayConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val densityDpi: Int = 240,
)
