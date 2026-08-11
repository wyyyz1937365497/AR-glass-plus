package com.example.ar_glass_plus.root

/** Current density state of one display. */
data class DensityInfo(
    val physicalDpi: Int,
    val overrideDpi: Int?,
)

/**
 * Per-display density control via `wm density [-d DISPLAY_ID]`.
 * Command syntax is probed on-device, never assumed for the OEM build.
 */
class DisplayDensityController(private val shell: RootShell) {

    suspend fun read(displayId: Int): DensityInfo? {
        val r = shell.exec("wm density -d $displayId")
        if (r.exitCode != 0) return null
        val physical = Regex("Physical density: (\\d+)")
            .find(r.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val override = Regex("Override density: (\\d+)")
            .find(r.stdout)?.groupValues?.get(1)?.toIntOrNull()
        return DensityInfo(physicalDpi = physical, overrideDpi = override)
    }

    suspend fun set(displayId: Int, dpi: Int): RootResult =
        shell.exec("wm density $dpi -d $displayId")

    suspend fun reset(displayId: Int): RootResult =
        shell.exec("wm density reset -d $displayId")
}
