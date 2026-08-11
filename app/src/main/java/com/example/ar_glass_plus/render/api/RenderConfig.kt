package com.example.ar_glass_plus.render.api

/** Backend-agnostic render configuration. */
data class RenderConfig(
    val outputWidth: Int,
    val outputHeight: Int,
    val mode: RenderMode = RenderMode.PASSTHROUGH_2D,
)
