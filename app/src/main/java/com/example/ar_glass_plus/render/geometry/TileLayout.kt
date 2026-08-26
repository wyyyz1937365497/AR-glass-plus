package com.example.ar_glass_plus.render.geometry

/**
 * Multi-VD bring-up compositor (smoke test) tile math: splits a region into
 * a uniform grid and returns the tile for one slot. Pure Kotlin — the GL
 * adapter consumes the result; nothing here knows about GL or displays.
 *
 * This is NOT the final spatial layout: P4.7C replaces it with world-space
 * placement (see SpatialPose), at which point tileRect becomes one placement
 * strategy among others.
 */
object TileLayout {

    /**
     * @param slot grid cell index, row-major: 0=TL 1=TR (2=BL 3=BR for 2x2).
     * @param columns grid width from SpatialWindowModel.TILE_COLUMNS.
     * @param rows grid height from SpatialWindowModel.TILE_ROWS.
     * @throws IllegalArgumentException if slot is outside the grid.
     */
    fun tileRect(slot: Int, columns: Int, rows: Int, region: PixelRect): PixelRect {
        require(columns > 0 && rows > 0) { "columns=$columns rows=$rows must be positive" }
        require(slot in 0 until columns * rows) { "slot $slot outside ${columns}x$rows grid" }

        val col = slot % columns
        val row = slot / columns
        // Float split: odd sizes divide evenly enough for a smoke-test grid;
        // the last column/row keeps any rounding remainder.
        val tileW = region.width / columns
        val tileH = region.height / rows
        val left = region.left + col * tileW
        val top = region.top + row * tileH
        val right = if (col == columns - 1) region.right else region.left + (col + 1) * tileW
        val bottom = if (row == rows - 1) region.bottom else region.top + (row + 1) * tileH
        return PixelRect(left, top, right, bottom)
    }
}
