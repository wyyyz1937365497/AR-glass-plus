package com.example.ar_glass_plus.input.uinput

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root process service: creates a REAL /dev/uinput REL mouse and pins it to
 * the content VirtualDisplay via InputManagerGlobal.setInputDeviceDisplayAssociation.
 * Runs as root uid (libsu RootService), so no Shizuku is needed.
 *
 * Derived from pgratz1/AR-Touchpad MouseService (Apache-2.0); Shizuku
 * UserService replaced by libsu RootService, target display is our hidden
 * contentDisplayId (not the physical glasses output).
 */
class RootMouseService : RootService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uinputReady = false
    private var uinputDescriptor: String? = null
    private var targetDisplayId = -1
    private var displayWidth = 0
    private var displayHeight = 0
    private var cursorX = 0f
    private var cursorY = 0f
    private var accumX = 0f
    private var accumY = 0f
    private var accumScrollX = 0f
    private var accumScrollY = 0f
    private var currentButtons = 0
    private var initJob: Job? = null

    private val stub = object : IRootMouseService.Stub() {
        override fun setDisplay(displayId: Int, width: Int, height: Int) =
            this@RootMouseService.setDisplay(displayId, width, height)

        override fun moveMouse(dx: Float, dy: Float) =
            this@RootMouseService.moveMouse(dx, dy)

        override fun click(x: Float, y: Float, button: Int) =
            this@RootMouseService.click(x, y, button)

        override fun scroll(dx: Float, dy: Float) =
            this@RootMouseService.scroll(dx, dy)

        override fun mouseDown() = this@RootMouseService.mouseDown()

        override fun mouseUp() = this@RootMouseService.mouseUp()

        override fun pressKey(keycode: Int) = this@RootMouseService.pressKey(keycode)

        override fun scrollDrag(dy: Float, action: Int) =
            this@RootMouseService.scrollDrag(dy, action)

        override fun destroy() = this@RootMouseService.destroy()
    }

    override fun onBind(intent: android.content.Intent): IBinder = stub

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "root mouse service started (uid=${android.os.Process.myUid()})")
        initJob = scope.launch { initUinput() }
    }

    private suspend fun initUinput() {
        val fd = UinputNative.nOpen()
        if (fd < 0) {
            Log.e(TAG, "uinput open failed fd=$fd")
            return
        }
        for (bit in intArrayOf(EV_SYN, EV_KEY, EV_REL)) {
            UinputNative.nIoctl(UI_SET_EVBIT, bit)
        }
        for (bit in intArrayOf(REL_X, REL_Y, REL_WHEEL, REL_HWHEEL)) {
            UinputNative.nIoctl(UI_SET_RELBIT, bit)
        }
        for (bit in intArrayOf(BTN_LEFT, BTN_RIGHT, BTN_MIDDLE)) {
            UinputNative.nIoctl(UI_SET_KEYBIT, bit)
        }
        UinputNative.nWriteDevInfo(DEVICE_NAME)
        UinputNative.nIoctl(UI_DEV_CREATE, 0)
        // Let InputReader enumerate the device.
        delay(400)
        uinputReady = true
        uinputDescriptor = findUinputDescriptor()
        Log.i(TAG, "uinput ready, descriptor=$uinputDescriptor")
        if (targetDisplayId >= 0) {
            uinputDescriptor?.let { associateDeviceToDisplay(it, targetDisplayId) }
        }
    }

    // ── IRootMouseService ──

    fun setDisplay(displayId: Int, width: Int, height: Int) {
        targetDisplayId = displayId
        displayWidth = width
        displayHeight = height
        cursorX = width / 2f
        cursorY = height / 2f
        accumX = 0f
        accumY = 0f
        uinputDescriptor?.let { associateDeviceToDisplay(it, displayId) }
        // 1px nudge wakes the cursor on the new display.
        scope.launch {
            moveMouseInternal(1f, 1f)
            delay(50)
            moveMouseInternal(-1f, -1f)
        }
        Log.i(TAG, "setDisplay: contentDisplayId=$displayId ${width}x$height")
    }

    fun moveMouse(dx: Float, dy: Float) {
        // OPPO ColorOS lacks InputManagerGlobal.setInputDeviceDisplayAssociation,
        // so uinput association is unavailable. Use displayId-stamped injected
        // motion events instead (same approach as AR-Touchpad pressKey).
        moveMouseInject(dx, dy)
    }

    fun click(x: Float, y: Float, button: Int) {
        cursorX = x
        cursorY = y
        injectButton(MotionEvent.ACTION_DOWN, button)
        scope.launch {
            delay(50)
            injectButton(MotionEvent.ACTION_UP, button)
        }
    }

    fun scroll(dx: Float, dy: Float) {
        injectScroll(dx, dy)
    }

    fun mouseDown() {
        injectButton(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
    }

    fun mouseUp() {
        injectButton(MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY)
    }

    fun pressKey(androidKeycode: Int) {
        try {
            val downTime = SystemClock.uptimeMillis()
            val keyEvent = android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_DOWN, androidKeycode, 0)
            val upEvent = android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_UP, androidKeycode, 0)
            setEventDisplayId(keyEvent, targetDisplayId)
            setEventDisplayId(upEvent, targetDisplayId)
            injectKeyEvent(keyEvent)
            injectKeyEvent(upEvent)
            Log.i(TAG, "injected key $androidKeycode -> display $targetDisplayId")
        } catch (e: Throwable) {
            Log.e(TAG, "pressKey failed", e)
        }
    }

    private fun setEventDisplayId(event: android.view.InputEvent, displayId: Int) {
        try {
            event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        } catch (e: Throwable) {
            // Older API: ignore
        }
    }

    private fun injectKeyEvent(event: android.view.InputEvent) {
        val mgr = inputManagerGlobal()
        mgr.javaClass
            .getMethod("injectInputEvent", android.view.InputEvent::class.java, java.lang.Integer.TYPE)
            .invoke(mgr, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
    }

    // ── injected-mouse implementations (OPPO lacks input→display association) ──

    private fun moveMouseInject(dx: Float, dy: Float) {
        if (targetDisplayId < 0) return
        accumX += dx
        accumY += dy
        val idx = accumX.toInt()
        val idy = accumY.toInt()
        if (idx == 0 && idy == 0) return
        accumX -= idx
        accumY -= idy
        cursorX = (cursorX + idx).coerceIn(0f, (displayWidth - 1).coerceAtLeast(0).toFloat())
        cursorY = (cursorY + idy).coerceIn(0f, (displayHeight - 1).coerceAtLeast(0).toFloat())
        injectMotion(MotionEvent.ACTION_MOVE, cursorX, cursorY, currentButtons)
    }

    private fun injectButton(action: Int, button: Int) {
        if (targetDisplayId < 0) return
        currentButtons = if (action == MotionEvent.ACTION_DOWN) button else 0
        injectMotion(action, cursorX, cursorY, currentButtons)
    }

    private fun injectScroll(dx: Float, dy: Float) {
        if (targetDisplayId < 0) {
            Log.w(TAG, "scroll ignored, no target display")
            return
        }
        accumScrollX += dx / SCROLL_DETENT_PX
        accumScrollY += dy / SCROLL_DETENT_PX
        val stepsX = accumScrollX.toInt()
        val stepsY = accumScrollY.toInt()
        if (stepsX == 0 && stepsY == 0) {
            Log.i(TAG, "scroll accumulated dx=$dx dy=$dy (steps 0)")
            return
        }
        accumScrollX -= stepsX
        accumScrollY -= stepsY
        Log.i(TAG, "scroll -> REL steps X=$stepsX Y=$stepsY")
        // Mouse-scroll semantics: ACTION_SCROLL + AXIS_VSCROLL at the LIST ZONE
        // (buttons at the top would swallow the scroll).
        injectMotion(
            MotionEvent.ACTION_SCROLL,
            displayWidth / 2f,
            displayHeight * SCROLL_ZONE_RATIO,
            0,
            axisVScroll = -stepsY.toFloat(),
        )
    }

    private fun injectMotion(
        action: Int,
        x: Float,
        y: Float,
        button: Int,
        axisVScroll: Float = 0f,
    ) {
        try {
            val now = SystemClock.uptimeMillis()
            val metaState = 0
            val props = android.view.MotionEvent.PointerProperties().apply {
                id = 0
                toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
            }
            val coords = android.view.MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                if (action == MotionEvent.ACTION_SCROLL) {
                    setAxisValue(MotionEvent.AXIS_VSCROLL, axisVScroll)
                }
            }
            val buttons = if (action == MotionEvent.ACTION_DOWN) button else 0
            val event = android.view.MotionEvent.obtain(
                now, now, action, 1, arrayOf(props), arrayOf(coords),
                metaState, buttons, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_MOUSE, 0,
            )
            setEventDisplayId(event, targetDisplayId)
            injectInputEvent(event)
            Log.i(TAG, "injected action=$action x=$x y=$y buttons=$currentButtons -> display $targetDisplayId")
            event.recycle()
        } catch (e: Throwable) {
            Log.e(TAG, "injectMotion($action) failed: ${e.message}")
        }
    }

    private fun injectInputEvent(event: android.view.InputEvent) {
        val mgr = inputManagerGlobal()
        mgr.javaClass
            .getMethod("injectInputEvent", android.view.InputEvent::class.java, java.lang.Integer.TYPE)
            .invoke(mgr, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
    }

    // Touch-drag scroll: DOWN/MOVE/UP at content center (SOURCE_TOUCHSCREEN).
    private var scrollDragY = 0f
    private var scrollDragStarted = false

    fun scrollDrag(dy: Float, action: Int) {
        if (targetDisplayId < 0) return
        // Scroll zone: lower area of the content (lists live there; the top
        // holds buttons that would swallow the touch).
        val zoneY = displayHeight * SCROLL_ZONE_RATIO
        // Touch y stays INSIDE the list zone while dragging (clamped), so the
        // pointer never runs off into the top button area.
        val zoneTop = displayHeight * SCROLL_ZONE_MIN
        val zoneBottom = displayHeight * SCROLL_ZONE_MAX
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                scrollDragStarted = true
                scrollDragY = zoneY
                injectTouch(ACTION_DOWN_T, displayWidth / 2f, scrollDragY)
            }
            MotionEvent.ACTION_MOVE -> {
                // Robust: if the client's async DOWN raced behind MOVE, start the
                // drag here so y never clamps to the top of the content.
                if (!scrollDragStarted) {
                    scrollDragStarted = true
                    scrollDragY = zoneY
                    injectTouch(ACTION_DOWN_T, displayWidth / 2f, scrollDragY)
                }
                scrollDragY = (scrollDragY + dy).coerceIn(zoneTop, zoneBottom)
                injectTouch(ACTION_MOVE_T, displayWidth / 2f, scrollDragY)
            }
            MotionEvent.ACTION_UP -> {
                scrollDragY = (scrollDragY + dy).coerceIn(zoneTop, zoneBottom)
                injectTouch(ACTION_UP_T, displayWidth / 2f, scrollDragY)
                scrollDragStarted = false
            }
        }
        Log.i(TAG, "scrollDrag action=$action y=$scrollDragY -> display $targetDisplayId")
    }

    private var touchDownTime = 0L
    private var touchEventTime = 0L

    private fun injectTouch(action: Int, x: Float, y: Float) {
        try {
            val now = SystemClock.uptimeMillis()
            if (action == ACTION_DOWN_T) {
                touchDownTime = now
                touchEventTime = now
            } else {
                // Timestamps MUST advance per event or the touch sequence is
                // rejected as invalid (this is why scrollDrag never scrolled).
                touchEventTime = maxOf(touchEventTime + EVENT_STEP_MS, now)
            }
            val props = android.view.MotionEvent.PointerProperties().apply {
                id = 0
                toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = android.view.MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
            }
            val event = android.view.MotionEvent.obtain(
                touchDownTime, touchEventTime, action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            setEventDisplayId(event, targetDisplayId)
            injectInputEvent(event)
            event.recycle()
        } catch (e: Throwable) {
            Log.e(TAG, "injectTouch($action) failed: ${e.message}")
        }
    }

    fun destroy() {
        UinputNative.nClose()
        uinputReady = false
        uinputDescriptor = null
        Log.i(TAG, "destroyed")
    }

    // ── internals ──

    private fun moveMouseInternal(dx: Float, dy: Float) {
        if (!uinputReady) return
        accumX += dx
        accumY += dy
        val idx = accumX.toInt()
        val idy = accumY.toInt()
        if (idx == 0 && idy == 0) return
        accumX -= idx
        accumY -= idy
        cursorX = (cursorX + idx).coerceIn(0f, (displayWidth - 1).coerceAtLeast(0).toFloat())
        cursorY = (cursorY + idy).coerceIn(0f, (displayHeight - 1).coerceAtLeast(0).toFloat())
        event(EV_REL, REL_X, idx)
        event(EV_REL, REL_Y, idy)
        sync()
    }

    private fun event(type: Int, code: Int, value: Int) {
        UinputNative.nWriteEvent(type, code, value)
    }

    private fun sync() {
        UinputNative.nWriteEvent(EV_SYN, SYN_REPORT, 0)
    }

    /**
     * Finds our uinput device's Android InputDevice descriptor by name via
     * InputManagerGlobal reflection (public API class).
     */
    private fun findUinputDescriptor(): String? {
        return try {
            val mgr = inputManagerGlobal()
            val ids = GLOBAL_CLASS.getMethod("getInputDeviceIds").invoke(mgr) as IntArray
            for (id in ids) {
                val dev = GLOBAL_CLASS.getMethod("getInputDevice", Int::class.javaPrimitiveType)
                    .invoke(mgr, id) as android.view.InputDevice?
                if (dev != null && dev.name == DEVICE_NAME) {
                    return dev.descriptor
                }
            }
            null
        } catch (e: Throwable) {
            Log.e(TAG, "findUinputDescriptor failed", e)
            null
        }
    }

    private fun associateDeviceToDisplay(descriptor: String, displayId: Int) {
        val instance = try {
            inputManagerGlobal()
        } catch (e: Throwable) {
            Log.e(TAG, "getInstance failed: ${e.message}")
            return
        }
        try {
            GLOBAL_CLASS.getMethod(
                "setInputDeviceDisplayAssociation",
                String::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(instance, descriptor, displayId)
            Log.i(TAG, "associated $descriptor -> display $displayId")
        } catch (e: Throwable) {
            Log.e(TAG, "direct assoc failed (${e.message}), trying mIm fallback")
            try {
                val mImField = GLOBAL_CLASS.getDeclaredField("mIm")
                mImField.isAccessible = true
                val iim = mImField.get(instance)
                val method = iim.javaClass.getMethod(
                    "setInputDeviceDisplayAssociation",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                method.invoke(iim, descriptor, displayId)
                Log.i(TAG, "associated $descriptor -> display $displayId (via mIm)")
            } catch (e2: Throwable) {
                Log.e(TAG, "mIm fallback failed: ${e2.message}")
            }
        }
    }

    private fun inputManagerGlobal(): Any =
        GLOBAL_CLASS.getMethod("getInstance").invoke(null)

    override fun onDestroy() {
        initJob?.cancel()
        destroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RootMouseSvc"
        const val DEVICE_NAME = "AR Glass Mouse"
        const val SCROLL_DETENT_PX = 20f

        // Linux input constants (match uinput_jni.cpp)
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_REL = 2
        const val SYN_REPORT = 0
        const val REL_X = 0
        const val REL_Y = 1
        const val REL_HWHEEL = 6
        const val REL_WHEEL = 8
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112

        // uinput ioctls
        const val UI_SET_EVBIT = 0x40045564
        const val UI_SET_KEYBIT = 0x40045565
        const val UI_SET_RELBIT = 0x40045566
        const val UI_DEV_CREATE = 0x5501
        const val SCROLL_ZONE_RATIO = 0.75f
        const val SCROLL_ZONE_MIN = 0.5f
        const val SCROLL_ZONE_MAX = 0.9f
        const val EVENT_STEP_MS = 16L

        // Touch action constants (MotionEvent ACTION_*)
        const val ACTION_DOWN_T = android.view.MotionEvent.ACTION_DOWN
        const val ACTION_MOVE_T = android.view.MotionEvent.ACTION_MOVE
        const val ACTION_UP_T = android.view.MotionEvent.ACTION_UP

        // Hidden/SystemApi class (absent from compileSdk 37 android.jar but
        // present at runtime on API 36): must use reflection.
        val GLOBAL_CLASS: Class<*> = Class.forName("android.hardware.input.InputManagerGlobal")


    }
}
