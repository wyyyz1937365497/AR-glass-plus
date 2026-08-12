package com.example.ar_glass_plus.render.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host-JVM tests for the pure-Kotlin geometry engine.
 * Scenario sizes mirror production: source 1280x720, output 1920x1080,
 * SBS eye regions 960x1080.
 */
class GeometryTest {

    private val resolver = GeometryResolver()
    private val source = PixelSize(1280f, 720f)

    private fun fit(region: PixelRect, rotation: ContentRotation = ContentRotation.DEG_0): ResolvedGeometry =
        resolver.resolve(source, region, GeometryConfig(AspectMode.FIT, rotation))

    // 13. content center -> output center
    @Test
    fun contentCenterMapsToOutputCenter() {
        val g = fit(PixelRect(0f, 0f, 1920f, 1080f))
        val p = GeometryMapper.mapContentToOutput(PixelPoint(640f, 360f), g)
        assertEquals(960f, p!!.x, 0.01f)
        assertEquals(540f, p.y, 0.01f)
    }

    // 14. output center -> content center
    @Test
    fun outputCenterMapsToContentCenter() {
        val g = fit(PixelRect(0f, 0f, 1920f, 1080f))
        val p = GeometryMapper.mapOutputToContent(PixelPoint(960f, 540f), g)
        assertEquals(640f, p!!.x, 0.01f)
        assertEquals(360f, p.y, 0.01f)
    }

    // 15. letterbox bars -> null; content center -> valid
    @Test
    fun letterboxInverseReturnsNull() {
        val g = fit(PixelRect(0f, 0f, 960f, 1080f))
        // 16:9 into 8:9 eye region -> 960x540 centered, 270px bars top/bottom
        assertEquals(960f, g.destinationRect.width, 0.01f)
        assertEquals(540f, g.destinationRect.height, 0.01f)
        assertEquals(270f, g.destinationRect.top, 0.01f)

        assertNull(GeometryMapper.mapOutputToContent(PixelPoint(480f, 100f), g))
        assertNull(GeometryMapper.mapOutputToContent(PixelPoint(480f, 1040f), g))

        val p = GeometryMapper.mapOutputToContent(PixelPoint(480f, 540f), g)
        assertEquals(640f, p!!.x, 0.01f)
        assertEquals(360f, p.y, 0.01f)
    }

    // 16. SBS left/right centers -> same content coordinate
    @Test
    fun sbsBothEyesMapToSameContentPoint() {
        val left = fit(PixelRect(0f, 0f, 960f, 1080f))
        val right = fit(PixelRect(960f, 0f, 1920f, 1080f))
        val pl = GeometryMapper.mapOutputToContent(PixelPoint(480f, 540f), left)!!
        val pr = GeometryMapper.mapOutputToContent(PixelPoint(1440f, 540f), right)!!
        assertEquals(pl.x, pr.x, 0.01f)
        assertEquals(pl.y, pr.y, 0.01f)
        assertEquals(640f, pl.x, 0.01f)
        assertEquals(360f, pl.y, 0.01f)
    }

    // FILL: crop is reflected in the inverse mapping (left edge -> x=320)
    @Test
    fun fillCropMapsEdgesCorrectly() {
        val g = resolver.resolve(source, PixelRect(0f, 0f, 960f, 1080f), GeometryConfig(AspectMode.FILL))
        assertEquals(0.25f, g.sourceRect.left, 0.01f)
        assertEquals(0.75f, g.sourceRect.right, 0.01f)
        // destination fills the region
        assertEquals(960f, g.destinationRect.width, 0.01f)
        assertEquals(1080f, g.destinationRect.height, 0.01f)
        // output left edge -> cropped source x=320 (not 0)
        val p = GeometryMapper.mapOutputToContent(PixelPoint(0f, 540f), g)
        assertEquals(320f, p!!.x, 0.01f)
    }

    // rotation 90: center still maps to center
    @Test
    fun rotation90KeepsCenter() {
        val g = fit(PixelRect(0f, 0f, 1920f, 1080f), ContentRotation.DEG_90)
        val p = GeometryMapper.mapContentToOutput(PixelPoint(640f, 360f), g)
        assertEquals(960f, p!!.x, 0.01f)
        assertEquals(540f, p.y, 0.01f)
    }

    // round trip: content -> output -> content is identity (inside content)
    @Test
    fun roundTripIsIdentity() {
        val g = fit(PixelRect(0f, 0f, 960f, 1080f))
        for (point in listOf(PixelPoint(100f, 100f), PixelPoint(640f, 360f), PixelPoint(1200f, 700f))) {
            val out = GeometryMapper.mapContentToOutput(point, g)!!
            val back = GeometryMapper.mapOutputToContent(out, g)!!
            assertEquals(point.x, back.x, 0.01f)
            assertEquals(point.y, back.y, 0.01f)
        }
    }
}
