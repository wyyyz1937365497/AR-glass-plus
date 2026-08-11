package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30

/**
 * Procedurally drawn calibration pattern (fullscreen quad + fragment shader),
 * NOT a FrameSource: it is renderer-generated content, not an external frame
 * producer. Designed to expose viewport/aspect/scaling/overscan/color issues:
 * RGB quadrants, gradient, checkerboard, center cross, border, L/R markers.
 */
class GlTestPattern(private val program: GlProgram) {

    private var vao = 0
    private var vbo = 0

    // TRIANGLE_STRIP quad: (pos.x, pos.y, uv.u, uv.v)
    private val vertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    )

    init {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        vao = ids[0]
        vbo = ids[1]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            java.nio.ByteBuffer
                .allocateDirect(vertices.size * Float.SIZE_BYTES)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices)
                .position(0),
            GLES30.GL_STATIC_DRAW,
        )
        val posLoc = program.attribLocation("aPos")
        val uvLoc = program.attribLocation("aUv")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(uvLoc)
        GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 2 * Float.SIZE_BYTES)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun delete() {
        val ids = intArrayOf(vbo, vao)
        GLES30.glDeleteBuffers(1, ids, 1)
        GLES30.glDeleteVertexArrays(1, ids, 0)
        vbo = 0
        vao = 0
    }
}
