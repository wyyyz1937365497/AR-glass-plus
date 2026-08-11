package com.example.ar_glass_plus.source

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hidden content VirtualDisplay: apps launched here render into the output
 * Surface, which the GL pipeline consumes as a texture. This is the content
 * display — NOT the RayNeo output display (see AGENTS.md model:
 * tabletDisplayId / outputDisplayId / contentDisplayId).
 *
 * Flags: PUBLIC | OWN_CONTENT_ONLY — public so third-party apps may place
 * windows on it; OWN_CONTENT_ONLY so it never auto-mirrors the default
 * display. NO AUTO_MIRROR, NO PRESENTATION (it is a content environment, not
 * an output device, and must not pollute ExternalDisplayController).
 */
class VirtualDisplaySource(
    context: Context,
    private val config: VirtualDisplayConfig = VirtualDisplayConfig(),
) : FrameSource {

    private val displayManager = context.applicationContext
        .getSystemService(DisplayManager::class.java)

    private val _state = MutableStateFlow<VirtualDisplayState>(VirtualDisplayState.Stopped)
    val state: StateFlow<VirtualDisplayState> = _state.asStateFlow()

    private var virtualDisplay: VirtualDisplay? = null

    /** The content display id (may be -1 before start / after stop). */
    val contentDisplayId: Int
        get() = (_state.value as? VirtualDisplayState.Running)?.displayId
            ?: Display.INVALID_DISPLAY

    override suspend fun start(output: Surface, config: SourceConfig) {
        releaseInternal()
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        virtualDisplay = displayManager.createVirtualDisplay(
            VD_NAME,
            this.config.width,
            this.config.height,
            this.config.densityDpi,
            output,
            flags,
        )
        val vd = virtualDisplay
        if (vd != null) {
            _state.value = VirtualDisplayState.Running(
                displayId = vd.displayIdCompat(),
                width = this.config.width,
                height = this.config.height,
                densityDpi = this.config.densityDpi,
            )
            Log.i(
                TAG,
                "contentDisplayId=${_state.value} created (${this.config.width}x" +
                    "${this.config.height}@${this.config.densityDpi}dpi, flags=0x${Integer.toHexString(flags)})",
            )
        } else {
            Log.w(TAG, "createVirtualDisplay failed")
        }
    }

    override fun stop() {
        releaseInternal()
    }

    private fun releaseInternal() {
        val vd = virtualDisplay
        if (vd != null) {
            vd.release()
            virtualDisplay = null
            _state.value = VirtualDisplayState.Stopped
            Log.i(TAG, "contentDisplayId released")
        }
    }

    @SuppressLint("NewApi")
    private fun VirtualDisplay.displayIdCompat(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display.displayId
        } else {
            Display.INVALID_DISPLAY
        }

    private companion object {
        const val TAG = "VDSource"
        const val VD_NAME = "ARGlassContent"
    }
}
