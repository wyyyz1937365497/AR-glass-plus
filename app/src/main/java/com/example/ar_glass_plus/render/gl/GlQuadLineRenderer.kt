package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import com.example.ar_glass_plus.render.geometry.PixelPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Focus outline for the spatial renderer: draws a line loop through a
 * window's four PROJECTED corners (output pixels, top-left origin). Works
 * for arbitrary rotated/perspective quads — no axis-aligned rect assumption.
 */
class GlQuadLineRenderer {

    private var program: GlProgram? = null
    private var vao = 0
    private var vbo = 0
    private var ready = false

    private val scratch = ByteBuffer
        .allocateDirect(4 * 2 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /** GL thread only. */
    fun onContextCreated() {
        program = GlProgram(VERTEX_SRC, FRAGMENT_SRC)
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        vao = ids[0]
        vbo = ids[1]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            4 * 2 * Float.SIZE_BYTES,
            null,
            GLES30.GL_STREAM_DRAW,
        )
        val posLoc = program!!.attribLocation("aPos")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindVertexArray(0)
        ready = true
    }

    /**
     * GL thread only. [points] must contain exactly 4 corners in order
     * TL,TR,BR,BL (region-relative output px). [fbWidth]/[fbHeight] are the
     * CURRENT region's size (NDC maps onto the active viewport).
     */
    fun draw(points: List<PixelPoint>, fbWidth: Int, fbHeight: Int, color: Int) {
        if (!ready || fbWidth <= 0 || fbHeight <= 0 || points.size != 4) return
        val p = program ?: return

        scratch.position(0)
        for (pt in points) {
            scratch.put(pt.x)
            scratch.put(pt.y)
        }
        scratch.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 4 * 2 * Float.SIZE_BYTES, scratch)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        p.use()
        GLES30.glUniform2f(p.uniformLocation("uViewport"), fbWidth.toFloat(), fbHeight.toFloat())
        GLES30.glUniform4f(
            p.uniformLocation("uColor"),
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            ((color shr 24) and 0xFF) / 255f,
        )
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    /** GL thread only. */
    fun delete() {
        if (!ready) return
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        vao = 0
        vbo = 0
        program?.delete()
        program = null
        ready = false
    }

    private companion object {
        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 aPos;   // region px, top-left origin
            uniform vec2 uViewport;
            void main() {
                vec2 ndc = vec2(aPos.x / uViewport.x * 2.0 - 1.0,
                                1.0 - aPos.y / uViewport.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
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
