package com.example.ar_glass_plus.render.spatial.calibration

import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.SpatialPoseRef
import com.example.ar_glass_plus.render.spatial.Vec3

/**
 * Pure-Kotlin description of the stereo/geometry calibration scene.
 * Everything is WORLD-space quads (rendered per eye by the same MVP path as
 * real windows) plus per-eye screen-space overlays (viewport-local NDC
 * triangles) that must visually align with their world counterparts.
 *
 * Visual check sheet (RayNeo acceptance):
 *  - eye order ......... arrow direction per half (left half must point LEFT)
 *  - UV / no mirror .... cross must stay a cross, L/R markers on their sides
 *  - principal point ... world center cross lands on the overlay cross
 *  - perspective ....... 0.5m/1m/2m depth ladder squares shrink with distance
 *  - stereo disparity .. near square's shift between halves > far square's
 *  - aspect ............ circle stays circular, square stays square
 */
object CalibrationScene {

    data class Quad(
        override val position: Vec3,
        override val widthMeters: Float,
        override val heightMeters: Float,
        override val orientation: Quat = Quat.IDENTITY,
        val colorArgb: Int,
    ) : SpatialPoseRef

    /** Viewport-local overlay triangle, NDC coordinates within one eye. */
    data class OverlayTriangle(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x3: Float,
        val y3: Float,
        val colorArgb: Int,
    )

    /** Per-eye 2D overlays in that eye's viewport-local NDC space. */
    data class EyeOverlay(
        val triangles: List<OverlayTriangle>,
    )

    data class Scene(
        val quads: List<Quad>,
        val leftOverlay: EyeOverlay,
        val rightOverlay: EyeOverlay,
    )

    // Reference depths (meters, negative Z forward).
    const val NEAR_Z = -0.8f
    const val MID_Z = -1.5f
    const val FAR_Z = -3.0f

    const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    const val COLOR_CYAN = 0xFF00E5FF.toInt()
    const val COLOR_MAGENTA = 0xFFFF4081.toInt()
    const val COLOR_GREEN = 0xFF76FF03.toInt()
    const val COLOR_AMBER = 0xFFFFC400.toInt()
    const val COLOR_DIM = 0x8080A0B0.toInt()

    fun build(): Scene {
        val quads = ArrayList<Quad>()

        // ── Depth ladder: 0.5m / 1m / 2m squares at increasing distance ──
        quads += ladderSquare(NEAR_Z, 0.5f, COLOR_CYAN)
        quads += ladderSquare(MID_Z, 1.0f, COLOR_MAGENTA)
        quads += ladderSquare(FAR_Z, 2.0f, COLOR_GREEN)

        // ── Zero-disparity-plane reference (mid depth) ──
        // Horizontal + vertical hairlines crossing at (0,0).
        quads += bar(Vec3(0f, 0f, MID_Z), 1.4f, 0.004f, COLOR_WHITE)
        quads += bar(Vec3(0f, 0f, MID_Z), 0.004f, 0.8f, COLOR_WHITE)
        // Center marker square (the world cross-check anchor).
        quads += bar(Vec3(0f, 0f, MID_Z), 0.05f, 0.05f, COLOR_AMBER)

        // ── Aspect probe: circle at mid depth, right side ──
        quads += circleQuads(Vec3(0.45f, 0f, MID_Z), 0.12f, COLOR_WHITE)

        // ── Axis probes: +X marker (right, near), +Y marker (up, near) ──
        quads += axisTickX(0.35f, NEAR_Z)
        quads += axisTickY(0.28f, NEAR_Z)

        return Scene(quads, leftOverlay(), rightOverlay())
    }

    // ── world-space builders ──

    /** Square outline at depth [z], side [sideM], as 4 thin bars. */
    private fun ladderSquare(z: Float, sideM: Float, color: Int): List<Quad> {
        val s = sideM / 2f
        val t = (sideM * 0.008f).coerceAtLeast(0.004f) // hairline scales with size
        return listOf(
            Quad(Vec3(0f, s, z), sideM, t, colorArgb = color),
            Quad(Vec3(0f, -s, z), sideM, t, colorArgb = color),
            Quad(Vec3(-s, 0f, z), t, sideM, colorArgb = color),
            Quad(Vec3(s, 0f, z), t, sideM, colorArgb = color),
        )
    }

    private fun bar(position: Vec3, w: Float, h: Float, color: Int): Quad =
        Quad(position, w, h, colorArgb = color)

    /** Circle approximated by tangential thin quads; an ellipse = aspect bug. */
    private fun circleQuads(center: Vec3, radius: Float, color: Int, segments: Int = 24): List<Quad> {
        val out = ArrayList<Quad>(segments)
        val ring = radius * 0.94f
        val inner = radius * 0.90f
        for (i in 0 until segments) {
            val a0 = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
            val a1 = ((i + 1).toFloat() / segments) * 2f * Math.PI.toFloat()
            val mid = (a0 + a1) / 2f
            val r = (ring + inner) / 2f
            val thickness = ring - inner
            val cx = center.x + kotlin.math.cos(mid) * r
            val cy = center.y + kotlin.math.sin(mid) * r
            val len = 2f * r * kotlin.math.sin((a1 - a0) / 2f) * 1.05f
            // Bar tangential to the arc: roll aligns its long axis.
            val rollDeg = Math.toDegrees(mid.toDouble()).toFloat()
            out += Quad(
                position = Vec3(cx, cy, center.z),
                widthMeters = len,
                heightMeters = thickness,
                orientation = Quat.fromEulerDegrees(rollDeg = rollDeg),
                colorArgb = color,
            )
        }
        return out
    }

    /** "+X →" probe: arrow of 3 quads pointing +X. */
    private fun axisTickX(y: Float, z: Float): List<Quad> {
        val c = COLOR_AMBER
        return listOf(
            Quad(Vec3(0.30f, y, z), 0.14f, 0.012f, colorArgb = c),
            Quad(Vec3(0.385f, y + 0.014f, z), 0.012f, 0.02f, orientation = Quat.fromEulerDegrees(rollDeg = 45f), colorArgb = c),
            Quad(Vec3(0.385f, y - 0.014f, z), 0.012f, 0.02f, orientation = Quat.fromEulerDegrees(rollDeg = -45f), colorArgb = c),
        )
    }

    /** "+Y ↑" probe: arrow of 3 quads pointing +Y. */
    private fun axisTickY(x: Float, z: Float): List<Quad> {
        val c = COLOR_AMBER
        return listOf(
            Quad(Vec3(x, 0.30f, z), 0.012f, 0.14f, colorArgb = c),
            Quad(Vec3(x - 0.014f, 0.385f, z), 0.02f, 0.012f, orientation = Quat.fromEulerDegrees(rollDeg = 45f), colorArgb = c),
            Quad(Vec3(x + 0.014f, 0.385f, z), 0.02f, 0.012f, orientation = Quat.fromEulerDegrees(rollDeg = -45f), colorArgb = c),
        )
    }

    // ── per-eye overlays (viewport-local NDC) ──

    /**
     * LEFT-eye overlay: a LEFT-pointing arrow near the top of the viewport
     * plus a center cross at (0,0) NDC (== principal point when the profile
     * centers it). If the user's left eye sees the RIGHT arrow, eye order is
     * swapped in the display chain.
     */
    private fun leftOverlay(): EyeOverlay = EyeOverlay(
        listOf(
            crossTriangles(COLOR_WHITE) + arrowTriangles(-0.55f, 0.8f, COLOR_CYAN),
        ).flatten(),
    )

    private fun rightOverlay(): EyeOverlay = EyeOverlay(
        listOf(
            crossTriangles(COLOR_WHITE) + arrowTriangles(0.55f, 0.8f, COLOR_MAGENTA),
        ).flatten(),
    )

    /** NDC cross at the viewport center: two thin quads (as 2 triangles each). */
    private fun crossTriangles(color: Int): List<OverlayTriangle> {
        val len = 0.10f
        val t = 0.004f
        return listOf(
            // horizontal bar
            tri(-len, -t, len, -t, len, t, color),
            tri(-len, -t, len, t, -len, t, color),
            // vertical bar
            tri(-t, -len, t, -len, t, len, color),
            tri(-t, -len, t, len, -t, len, color),
        )
    }

    /** Solid arrow pointing [dir] (+1 right / -1 left) at NDC y. */
    private fun arrowTriangles(dirX: Float, y: Float, color: Int): List<OverlayTriangle> {
        val s = 0.045f
        val cx = dirX * 0.6f
        val tip = cx + dirX * s * 1.6f
        val back = cx - dirX * s * 0.8f
        return listOf(
            // shaft
            tri(back, y - s * 0.35f, cx, y - s * 0.35f, cx, y + s * 0.35f, color),
            tri(back, y - s * 0.35f, cx, y + s * 0.35f, back, y + s * 0.35f, color),
            // head
            tri(cx - dirX * s * 0.6f, y - s, tip, y, cx - dirX * s * 0.6f, y + s, color),
        )
    }

    private fun tri(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        color: Int,
    ) = OverlayTriangle(x1, y1, x2, y2, x3, y3, color)
}
