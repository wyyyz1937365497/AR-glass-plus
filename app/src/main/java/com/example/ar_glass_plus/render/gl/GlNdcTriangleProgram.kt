package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import com.example.ar_glass_plus.render.spatial.calibration.CalibrationScene
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Viewport-local NDC triangle overlay (calibration arrows + center cross).
 * Each triangle is a separate draw with its own color; count is tiny
 * (~14 per eye) so no batching is warranted.
 */
class GlNdcTriangleProgram : GlProgram(VERTEX_SRC, FRAGMENT_SRC) {

    private var vao = 0
    private var vbo = 0
    private val colorLoc = uniformLocation("uColor")
    private val scratch = ByteBuffer
        .allocateDirect(3 * 2 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    init {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        vao = ids[0]
        vbo = ids[1]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 3 * 2 * Float.SIZE_BYTES, null, GLES30.GL_STREAM_DRAW)
        val posLoc = attribLocation("aPos")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindVertexArray(0)
    }

    /** GL thread only. NDC xy pairs; y up. */
    fun draw(triangles: List<CalibrationScene.OverlayTriangle>) {
        for (t in triangles) {
            scratch.position(0)
            scratch.put(t.x1); scratch.put(t.y1)
            scratch.put(t.x2); scratch.put(t.y2)
            scratch.put(t.x3); scratch.put(t.y3)
            scratch.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 3 * 2 * Float.SIZE_BYTES, scratch)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            use()
            GLES30.glUniform4f(
                colorLoc,
                ((t.colorArgb shr 16) and 0xFF) / 255f,
                ((t.colorArgb shr 8) and 0xFF) / 255f,
                (t.colorArgb and 0xFF) / 255f,
                ((t.colorArgb shr 24) and 0xFF) / 255f,
            )
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)
        }
    }

    override fun delete() {
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        vao = 0
        vbo = 0
        super.delete()
    }

    private companion object {
        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision highp float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        """.trimIndent()
    }
}
