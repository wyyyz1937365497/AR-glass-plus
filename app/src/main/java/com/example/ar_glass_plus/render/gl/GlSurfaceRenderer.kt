package com.example.ar_glass_plus.render.gl

import android.opengl.GLSurfaceView

/**
 * Bridges GLSurfaceView's render loop to the backend. The surface view owns
 * the EGL context and frame scheduling; this class only forwards callbacks.
 */
class GlSurfaceRenderer(private val backend: GlRenderBackend) : GLSurfaceView.Renderer {

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        backend.onGlContextCreated()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        backend.resize(width, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        backend.renderFrame()
    }
}
