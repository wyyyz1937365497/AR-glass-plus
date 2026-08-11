package com.example.ar_glass_plus.render.api

/**
 * Output composition mode. Extend with CROP/SCALE/HEAD_TRACKED/
 * DISTORTION_CORRECTED/DEPTH_STEREO as features land — do not implement
 * them preemptively.
 */
enum class RenderMode {
    /** Single texture drawn once, full output. */
    PASSTHROUGH_2D,

    /** Same frame drawn to both halves (side-by-side). */
    SBS_DUPLICATE,

    /** Left/right eye frames differ (true stereo). */
    SBS_STEREO,
}
