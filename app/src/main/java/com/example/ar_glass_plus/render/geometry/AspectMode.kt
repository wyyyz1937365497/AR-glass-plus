package com.example.ar_glass_plus.render.geometry

/**
 * How source content is placed into a target region.
 * FIT is the default; STRETCH is retained for future half-SBS anamorphic
 * transport where distortion is actually the correct format.
 */
enum class AspectMode {
    /** Full content, aspect preserved, letterbox bars. */
    FIT,

    /** Aspect preserved, region filled, source cropped. */
    FILL,

    /** Force-fill, distortion allowed. */
    STRETCH,
}
