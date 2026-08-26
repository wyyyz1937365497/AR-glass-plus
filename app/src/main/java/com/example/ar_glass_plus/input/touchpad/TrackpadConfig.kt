package com.example.ar_glass_plus.input.touchpad

import android.content.Context
import android.view.ViewConfiguration

/**
 * System gesture thresholds from ViewConfiguration so trackpad behavior
 * matches the platform's own GestureDetector. Values are dp-scaled to Compose
 * pointer coordinates (unscaled getters == dp).
 *
 * NOTE: compileSdk 37's android.jar removed getTouchSlop()/getDoubleTapSlop()
 * (unscaled getters), so they are read via reflection against the runtime
 * framework (present on the API 36 device) with AOSP constants as fallback.
 * getDoubleTapTimeout()/getLongPressTimeout() are still public static APIs.
 */
class TrackpadConfig(
    /** Max finger movement before a press becomes a move (dp). */
    val touchSlop: Float,
    /** Max distance between the two taps of a double tap (dp). */
    val doubleTapSlop: Float,
    /** Max interval between taps of a double tap (ms). */
    val doubleTapTimeout: Long,
    /** Hold time before a pressed second tap starts a drag (ms). */
    val longPressTimeout: Long,
) {
    constructor(context: Context) : this(
        touchSlop = (reflectInt(ViewConfiguration.get(context), "getTouchSlop")
            ?: DEFAULT_TOUCH_SLOP).toFloat(),
        doubleTapSlop = (reflectStaticInt("getDoubleTapSlop")
            ?: DEFAULT_DOUBLE_TAP_SLOP).toFloat(),
        doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong(),
        longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong(),
    )

    private companion object {
        // AOSP ViewConfiguration constants (unscaled dp values).
        const val DEFAULT_TOUCH_SLOP = 8
        const val DEFAULT_DOUBLE_TAP_SLOP = 100

        fun reflectInt(receiver: Any, method: String): Int? = try {
            receiver.javaClass.getMethod(method).invoke(receiver) as Int
        } catch (e: Throwable) {
            null
        }

        fun reflectStaticInt(method: String): Int? = try {
            ViewConfiguration::class.java.getMethod(method).invoke(null) as Int
        } catch (e: Throwable) {
            null
        }
    }
}
