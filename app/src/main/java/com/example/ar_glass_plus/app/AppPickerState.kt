package com.example.ar_glass_plus.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-switch requests from the control-panel picker to the running render
 * session (RenderDisplayActivity). Same pattern as RenderDisplaySession:
 * process-global object so the picker and the display-side activity can talk
 * without a shared Activity.
 *
 * A request just starts the package on the CURRENT content display — the
 * session/renderer/input never need rebuilding (same VirtualDisplay).
 */
object AppPickerState {

    /** A launch request: package + its launcher activity (may be null). */
    data class LaunchRequest(val packageName: String, val launcherClassName: String?)

    private val _launchRequests = MutableSharedFlow<LaunchRequest>(extraBufferCapacity = 8)
    val launchRequests: SharedFlow<LaunchRequest> = _launchRequests.asSharedFlow()

    /** Last app the user picked (shown as "当前运行 App" in the sidebar). */
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    /** Called when the user taps an app row. Safe from any thread. */
    fun requestLaunch(entry: AppEntry) {
        _currentApp.value = entry.packageName
        _launchRequests.tryEmit(LaunchRequest(entry.packageName, entry.launcherClassName))
    }
}
