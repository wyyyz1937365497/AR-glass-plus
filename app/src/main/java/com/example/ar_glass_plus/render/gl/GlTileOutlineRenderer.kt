package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import com.example.ar_glass_plus.render.geometry.PixelRect
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Focus tile outline for the multi-VD bring-up compositor: draws a border
 * ring around one output-space rect. Pure presentation — focus state comes
 * from the caller (focused window), never queried here. Structure mirrors
 * GlCursorRenderer (unit quad + NDC uniforms).
 */
class GlTileOutlineRenderer {

    private var program: GlProgram? = null
    private var vao = 0
    private var vbo = 0
    private var ready = false

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
        val vertices = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            ByteBuffer
                .allocateDirect(vertices.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices)
                .position(0),
            GLES30.GL_STATIC_DRAW,
        )
        val posLoc = program!!.attribLocation("aPos")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindVertexArray(0)
        ready = true
    }

    /**
     * GL thread only. Draws a [thicknessPx]-wide ring around [rect] (output
     * pixels, top-left origin) on a [fbWidth]x[fbHeight] framebuffer.
     */
    fun draw(rect: PixelRect, thicknessPx: Float, fbWidth: Int, fbHeight: Int, color: Int) {
        if (!ready || fbWidth <= 0 || fbHeight <= 0) return
        val p = program ?: return

        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        // Quad covers the rect plus the ring band on every side.
        val halfW = rect.width / 2f + thicknessPx
        val halfH = rect.height / 2f + thicknessPx

        p.use()
        GLES30.glUniform2f(p.uniformLocation("uCenter"), cx, cy)
        GLES30.glUniform2f(p.uniformLocation("uHalf"), halfW, halfH)
        GLES30.glUniform2f(p.uniformLocation("uViewport"), fbWidth.toFloat(), fbHeight.toFloat())
        GLES30.glUniform1f(p.uniformLocation("uThickness"), thicknessPx)
        GLES30.glUniform4f(
            p.uniformLocation("uColor"),
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            ((color shr 24) and 0xFF) / 255f,
        )
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
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
        // Quad in unit space [0,1]^2; the shader expands it around uCenter.
        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            uniform vec2 uCenter;   // rect center, output px (top-left origin)
            uniform vec2 uHalf;     // rect half-extent + ring band, output px
            uniform vec2 uViewport; // framebuffer px
            out vec2 vLocal;        // px offset from rect center
            void main() {
                vec2 local = aPos * 2.0 - 1.0;            // [-1,1]^2
                vec2 px = uCenter + local * uHalf;         // output px
                vec2 ndc = vec2(px.x / uViewport.x * 2.0 - 1.0,
                                1.0 - px.y / uViewport.y * 2.0);
                vLocal = local * uHalf;
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision highp float;
            in vec2 vLocal;         // px offset from rect center
            uniform vec2 uHalf;
            uniform float uThickness;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                // Signed distance outside the inner rect (= uHalf - band).
                vec2 d = abs(vLocal) - (uHalf - vec2(uThickness));
                if (max(d.x, d.y) > 0.0) discard;   // ring only, hollow center
                fragColor = uColor;
            }
        """.trimIndent()
    }
}
