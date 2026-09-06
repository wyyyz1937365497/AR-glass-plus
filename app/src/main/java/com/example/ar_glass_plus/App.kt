package com.example.ar_glass_plus

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.ar_glass_plus.display.ExternalDisplayController
import com.example.ar_glass_plus.display.ExternalDisplayState
import com.example.ar_glass_plus.display.ProjectionConsentAutomator
import com.example.ar_glass_plus.display.sbs.SbsDisplayModeCoordinator
import com.example.ar_glass_plus.display.sbs.SbsKernelController
import com.example.ar_glass_plus.display.sbs.SbsKernelState
import com.example.ar_glass_plus.display.sbs.SbsOperationResult
import com.example.ar_glass_plus.display.sbs.SbsReleaseReason
import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.api.UinputInputBackend
import com.example.ar_glass_plus.input.mouse.MouseController
import com.example.ar_glass_plus.input.touchpad.TrackpadConfig
import com.example.ar_glass_plus.input.touchpad.TrackpadGestureEngine
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.root.RootShellImpl
import com.example.ar_glass_plus.settings.UserPreferences
import com.example.ar_glass_plus.workspace.AndroidWorkspaceAppLauncher
import com.example.ar_glass_plus.workspace.AndroidWorkspaceHostPort
import com.example.ar_glass_plus.workspace.AndroidWorkspaceRootPort
import com.example.ar_glass_plus.workspace.RealInputSession
import com.example.ar_glass_plus.workspace.WorkspaceController
import com.example.ar_glass_plus.workspace.WorkspaceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process entry point. Owns the process-stable workspace controller and the
 * physical-display controller so Activity recreation (rotation, config change,
 * task migration) never produces a second controller while the rendered-host
 * Activity still holds the original one (review blocker: controller split-brain).
 */
class App : Application() {

    lateinit var displayController: ExternalDisplayController
        private set
    lateinit var shell: RootShellImpl
        private set
    lateinit var uinputBackend: UinputInputBackend
        private set
    lateinit var cursorController: CursorController
        private set
    lateinit var mouseController: MouseController
        private set
    lateinit var engine: TrackpadGestureEngine
        private set
    lateinit var sbsKernelController: SbsKernelController
        private set
    lateinit var userPreferences: UserPreferences
        private set
    lateinit var projectionConsentAutomator: ProjectionConsentAutomator
        private set

    val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var inputSession: RealInputSession
    private lateinit var workspaceController: WorkspaceController
    private lateinit var displayModeCoordinator: SbsDisplayModeCoordinator

    @Volatile
    private var startedMainActivities = 0

    @Volatile
    private var backgroundGeneration = 0L

    @Volatile
    private var disconnectGeneration = 0L


    /** Latest spatial renderer telemetry (published by RenderDisplayActivity). */
    @Volatile
    var renderStats: com.example.ar_glass_plus.render.gl.SpatialRenderStats =
        com.example.ar_glass_plus.render.gl.SpatialRenderStats()
        private set

    fun publishRenderStats(stats: com.example.ar_glass_plus.render.gl.SpatialRenderStats) {
        renderStats = stats
    }

    override fun onCreate() {
        super.onCreate()
        userPreferences = UserPreferences(applicationContext)
        displayController = ExternalDisplayController(applicationContext)
        shell = RootShellImpl()
        projectionConsentAutomator = ProjectionConsentAutomator(
            shell = shell,
            isEnabled = userPreferences::loadAutoConfirmProjection,
            isRayNeoAttached = ::isRayNeoUsbAttached,
        )
        val host = AndroidWorkspaceHostPort(applicationContext, displayController)
        val appLauncher = AndroidWorkspaceAppLauncher(applicationContext, shell)
        val root = AndroidWorkspaceRootPort(shell)
        val controller = WorkspaceSession.createController(host, appLauncher, root)
        workspaceController = controller
        WorkspaceSession.registerController(controller)
        controller.updateCalibration(userPreferences.loadCalibration())
        createInputStack(controller)
        cursorController.setSensitivity(userPreferences.loadPointerSensitivity())
        controller.setInputSession(inputSession)
        sbsKernelController = SbsKernelController(shell)
        displayModeCoordinator = SbsDisplayModeCoordinator(
            kernel = sbsKernelController,
            currentMode = { controller.state.renderMode },
            commitMode = controller::setRenderMode,
        )
        observeOutputLifecycle()
        observeRayNeoUsbLifecycle()
        registerActivityLifecycleCallbacks(activityCallbacks)
        requestProjectionConsentCheck("app-start")
    }

    suspend fun selectRenderMode(mode: RenderMode): SbsOperationResult {
        val result = displayModeCoordinator.select(mode)
        if (result is SbsOperationResult.Success) {
            requestProjectionConsentCheck("render-mode-${mode.name.lowercase()}")
        }
        return result
    }

    fun requestProjectionConsentCheck(reason: String) {
        inputScope.launch {
            projectionConsentAutomator.confirmIfPresent(reason)
        }
    }

    private fun createInputStack(controller: WorkspaceController) {
        uinputBackend = UinputInputBackend(applicationContext)
        cursorController = CursorController(
            onContentSize = {
                WorkspaceSession.store.state.value.content?.let { it.width to it.height }
            },
            cursorProvider = { WorkspaceSession.store.state.value.cursor },
            onCursorChanged = controller::updateCursor,
        )
        mouseController = MouseController(
            backend = uinputBackend,
            cursor = cursorController,
            cursorProvider = { WorkspaceSession.store.state.value.cursor },
            scope = inputScope,
        )
        engine = TrackpadGestureEngine(
            config = TrackpadConfig(applicationContext),
            scope = inputScope,
        )
        inputSession = RealInputSession(
            backend = uinputBackend,
            cursor = cursorController,
            mouse = mouseController,
            engine = engine,
        )
    }

    /**
     * Output hotplug ownership lives at process scope, not in MainActivity.
     * A software EDID reprobe briefly removes the display, so kernel release
     * is debounced; a real unplug remains disconnected and releases after the
     * grace interval.
     */
    private fun observeOutputLifecycle() {
        inputScope.launch {
            var lastOutputDisplayId: Int? = null
            displayController.state.collect { state ->
                when (state) {
                    is ExternalDisplayState.Connected -> {
                        disconnectGeneration += 1
                        val oldId = lastOutputDisplayId
                        if (oldId != null && oldId != state.displayId) {
                            workspaceController.onOutputDisconnected(oldId)
                        }
                        lastOutputDisplayId = state.displayId
                        workspaceController.onOutputConnected(state.displayId)
                    }

                    ExternalDisplayState.Disconnected -> {
                        requestProjectionConsentCheck("display-disconnected")
                        val oldId = lastOutputDisplayId ?: workspaceController.state.outputDisplayId
                        lastOutputDisplayId = null
                        if (oldId != null) workspaceController.onOutputDisconnected(oldId)

                        val generation = disconnectGeneration + 1
                        disconnectGeneration = generation
                        inputScope.launch {
                            while (true) {
                                delay(OUTPUT_DISCONNECT_GRACE_MS)
                                if (
                                    disconnectGeneration != generation ||
                                    displayController.state.value != ExternalDisplayState.Disconnected
                                ) {
                                    return@launch
                                }

                                val kernelState = sbsKernelController.state.value
                                val controlledSbsTransition =
                                    kernelState is SbsKernelState.Transitioning ||
                                        kernelState is SbsKernelState.Active
                                if (controlledSbsTransition && isRayNeoUsbAttached()) {
                                    // A DPTX EDID reprobe removes the logical
                                    // output display while the USB HID remains
                                    // physically attached. Re-scan, but keep
                                    // the lease; a real unplug removes HID too.
                                    displayController.refresh()
                                    continue
                                }

                                displayModeCoordinator.releaseAndReset(
                                    SbsReleaseReason.OUTPUT_DISCONNECTED,
                                )
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isRayNeoUsbAttached(): Boolean {
        val usbManager = getSystemService(UsbManager::class.java)
        return usbManager.deviceList.values.any { device ->
            device.vendorId == RAYNEO_VENDOR_ID && device.productId == RAYNEO_PRODUCT_ID
        }
    }

    private fun observeRayNeoUsbLifecycle() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            rayNeoUsbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private val rayNeoUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    requestProjectionConsentCheck("usb-attached")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> Unit
            }
        }
    }

    /** Main control-task backgrounding is the explicit session boundary. */
    private val activityCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            if (activity !is MainActivity) return
            startedMainActivities += 1
            backgroundGeneration += 1
            (displayController.state.value as? ExternalDisplayState.Connected)?.let {
                workspaceController.onOutputConnected(it.displayId)
            }
        }

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            if (activity !is MainActivity) return
            startedMainActivities = (startedMainActivities - 1).coerceAtLeast(0)
            val generation = backgroundGeneration + 1
            backgroundGeneration = generation
            inputScope.launch {
                delay(APP_BACKGROUND_GRACE_MS)
                if (startedMainActivities != 0 || backgroundGeneration != generation) return@launch

                workspaceController.stopWorkspace()
                displayModeCoordinator.releaseAndReset(SbsReleaseReason.APP_BACKGROUNDED)

                // Keep the idle controller ready when the same process returns.
                (displayController.state.value as? ExternalDisplayState.Connected)?.let {
                    workspaceController.onOutputConnected(it.displayId)
                }
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val APP_BACKGROUND_GRACE_MS = 900L
        const val OUTPUT_DISCONNECT_GRACE_MS = 2_000L
        const val RAYNEO_VENDOR_ID = 0x1BBB
        const val RAYNEO_PRODUCT_ID = 0xAF50
    }
}
