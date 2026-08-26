package com.example.ar_glass_plus.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the physical external display (RayNeo/HDMI via USB-C DP Alt Mode).
 *
 * Display ids are NOT stable across hotplug/reboot — every consumer must read
 * the current id from [state] instead of caching it.
 */
class ExternalDisplayController(context: Context) {

    private val displayManager =
        context.applicationContext.getSystemService(DisplayManager::class.java)

    private val _state = MutableStateFlow(discover())
    val state: StateFlow<ExternalDisplayState> = _state.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        Log.i(TAG, "initial state: ${_state.value}")
    }

    fun refresh() {
        _state.value = discover()
        Log.i(TAG, "refresh -> ${_state.value}")
    }

    /**
     * Physical external display: not the built-in, flagged PRESENTATION.
     * Display ids are not stable — always re-read.
     * NOTE: Display.isInternal() is API 37+; devices run API 36, so the
     * built-in display is identified by DEFAULT_DISPLAY id instead.
     */
    private fun discover(): ExternalDisplayState {
        val display = displayManager.displays
            .asSequence()
            .filterNot { it.displayId == Display.DEFAULT_DISPLAY }
            .filter { it.flags and Display.FLAG_PRESENTATION != 0 }
            .firstOrNull() ?: return ExternalDisplayState.Disconnected

        val mode = display.mode
        val metrics = DisplayMetrics().also { display.getMetrics(it) }
        return ExternalDisplayState.Connected(
            displayId = display.displayId,
            name = "External Display",
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            refreshRate = mode.refreshRate,
            densityDpi = metrics.densityDpi,
        )
    }

    /** Launch the glasses UI onto the external display. Returns false if none connected. */
    fun launchExternalActivity(context: Context): Boolean {
        val displayId = (state.value as? ExternalDisplayState.Connected)?.displayId
            ?: return false
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(
            Intent(context, ExternalDisplayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options.toBundle(),
        )
        Log.i(TAG, "launched ExternalDisplayActivity onto display $displayId")
        return true
    }

    /**
     * Launch the GL rendered-display host onto the exact controller-selected
     * output display id. Using the caller-supplied id (not a re-read of state)
     * closes the hotplug race where the physical display could change between
     * the controller's start decision and the actual launch.
     */
    fun launchRenderDisplay(context: Context, outputDisplayId: Int): Boolean {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(outputDisplayId)
        return try {
            context.startActivity(
                Intent(context, RenderDisplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                options.toBundle(),
            )
            Log.i(TAG, "launched RenderDisplayActivity onto display $outputDisplayId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "launchRenderDisplay($outputDisplayId) failed: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "ExtDisplayCtrl"
    }
}
