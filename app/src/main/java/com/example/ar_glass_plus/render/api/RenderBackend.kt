package com.example.ar_glass_plus.render.api

/**
 * Rendering backend abstraction. Must not expose backend-specific types
 * (EGL, Vk* etc.) so an OpenGL ES implementation can later be swapped for a
 * Vulkan one without touching callers.
 *
 * Decision (see AGENTS.md): OpenGL ES is the primary backend. Vulkan is
 * reserved and must NOT be implemented until profiling demonstrates a real
 * performance/feature need.
 */
interface RenderBackend {

    /** Attach to [target] and prepare [config]. */
    fun initialize(target: RenderTarget, config: RenderConfig)

    /** Output size changed (display hotplug / resolution change). */
    fun resize(width: Int, height: Int)

    fun setRenderMode(mode: RenderMode)

    /** Draw one frame. Caller drives the frame clock. */
    fun renderFrame()

    /** Detach and release all GPU resources. */
    fun release()
}
