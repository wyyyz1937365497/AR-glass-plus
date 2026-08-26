package com.example.ar_glass_plus.render.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLayoutTest {

    @Test
    fun twoByTwoQuadrantsMapExactRegion() {
        // Given
        val region = PixelRect(0f, 0f, 1920f, 1080f)

        // Then: slot 0=TL 1=TR 2=BL 3=BR, row-major.
        assertEquals(PixelRect(0f, 0f, 960f, 540f), TileLayout.tileRect(0, 2, 2, region))
        assertEquals(PixelRect(960f, 0f, 1920f, 540f), TileLayout.tileRect(1, 2, 2, region))
        assertEquals(PixelRect(0f, 540f, 960f, 1080f), TileLayout.tileRect(2, 2, 2, region))
        assertEquals(PixelRect(960f, 540f, 1920f, 1080f), TileLayout.tileRect(3, 2, 2, region))
    }

    @Test
    fun tilesTileTheRegionForOffsetAndOddSize() {
        // Given: non-origin region with odd (non-divisible) size.
        val region = PixelRect(100f, 50f, 100f + 1001f, 50f + 601f)

        // When
        val tiles = (0 until 4).map { TileLayout.tileRect(it, 2, 2, region) }

        // Then: all four tiles inside the region, union covers it, no gaps
        // between adjacent tiles, and the last column/row absorbs rounding.
        tiles.forEach {
            assertTrue(it.left >= region.left && it.top >= region.top)
            assertTrue(it.right <= region.right && it.bottom <= region.bottom)
        }
        assertEquals(region.left.toFloat(), tiles.minOf { it.left })
        assertEquals(region.top.toFloat(), tiles.minOf { it.top })
        assertEquals(region.right, tiles.maxOf { it.right })
        assertEquals(region.bottom, tiles.maxOf { it.bottom })
        assertEquals(tiles[0].right, tiles[1].left)
        assertEquals(tiles[2].right, tiles[3].left)
        assertEquals(tiles[0].bottom, tiles[2].top)
        assertEquals(tiles[1].bottom, tiles[3].top)
    }

    @Test
    fun lastColumnAndRowKeepRoundingRemainder() {
        // Given: 1001 wide -> nominal 500.5 per tile; last tile keeps the edge.
        val region = PixelRect(0f, 0f, 1001f, 601f)

        // When
        val tl = TileLayout.tileRect(0, 2, 2, region)
        val tr = TileLayout.tileRect(1, 2, 2, region)

        // Then
        assertEquals(500.5f, tl.right)
        assertEquals(500.5f, tr.left)
        assertEquals(1001f, tr.right)
    }

    @Test
    fun outOfRangeSlotThrows() {
        // Given
        val region = PixelRect(0f, 0f, 100f, 100f)

        // Then
        val negative = try {
            TileLayout.tileRect(-1, 2, 2, region)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        val tooBig = try {
            TileLayout.tileRect(4, 2, 2, region)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(negative)
        assertTrue(tooBig)
    }
}
