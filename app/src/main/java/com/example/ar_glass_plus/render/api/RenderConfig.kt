package com.example.ar_glass_plus.render.api

import com.example.ar_glass_plus.render.geometry.RenderMode

/** Backend-agnostic render configuration. */
data class RenderConfig(
    val outputWidth: Int,
    val outputHeight: Int,
    val mode: RenderMode = RenderMode.PASSTHROUGH_2D,
)
