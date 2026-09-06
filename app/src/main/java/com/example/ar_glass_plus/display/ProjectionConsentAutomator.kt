package com.example.ar_glass_plus.display

import android.os.SystemClock
import android.util.Log
import com.example.ar_glass_plus.root.RootShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of a narrowly scoped ColorOS projection-consent scan. */
enum class ProjectionConsentResult {
    CONFIRMED,
    RECENTLY_CONFIRMED,
    NOT_FOUND,
    DISABLED,
    RAYNEO_NOT_ATTACHED,
    DUMP_FAILED,
    CLICK_FAILED,
}

/**
 * Confirms only the exact ColorOS SystemUI external-display dialog observed on
 * the OPD2407. This is deliberately not a generic UI clicker:
 *
 * 1. the preference must be enabled;
 * 2. Air 4 Pro USB VID/PID must still be attached;
 * 3. package, title, message, positive-button id and text must all match;
 * 4. the tap coordinate is derived from that button's live accessibility
 *    bounds, never from a hard-coded screen position.
 */
class ProjectionConsentAutomator(
    private val shell: RootShell,
    private val isEnabled: () -> Boolean,
    private val isRayNeoAttached: () -> Boolean,
) {
    private val mutex = Mutex()
    private var lastConfirmedAtMs = 0L

    suspend fun confirmIfPresent(reason: String): ProjectionConsentResult = mutex.withLock {
        if (!isEnabled()) return@withLock ProjectionConsentResult.DISABLED
        if (!isRayNeoAttached()) return@withLock ProjectionConsentResult.RAYNEO_NOT_ATTACHED
        val now = SystemClock.elapsedRealtime()
        if (lastConfirmedAtMs > 0L && now - lastConfirmedAtMs < RECENT_CONFIRM_QUIET_MS) {
            Log.d(TAG, "skip duplicate dialog scan reason=$reason")
            return@withLock ProjectionConsentResult.RECENTLY_CONFIRMED
        }

        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            if (!isEnabled()) return@withLock ProjectionConsentResult.DISABLED
            if (!isRayNeoAttached()) return@withLock ProjectionConsentResult.RAYNEO_NOT_ATTACHED

            val dump = shell.exec(DUMP_COMMAND, DUMP_TIMEOUT_MS)
            if (dump.exitCode != 0 || dump.stdout.isBlank()) {
                Log.w(TAG, "dialog scan failed reason=$reason attempt=${attempt + 1} exit=${dump.exitCode}")
                if (attempt == MAX_ATTEMPTS - 1) {
                    return@withLock ProjectionConsentResult.DUMP_FAILED
                }
                return@repeat
            }

            val target = ProjectionConsentDialogMatcher.findPositiveButton(dump.stdout)
            if (target == null) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    Log.d(TAG, "target dialog not found reason=$reason")
                    return@withLock ProjectionConsentResult.NOT_FOUND
                }
                return@repeat
            }

            // Re-check the two gates after the relatively slow UI dump and
            // before issuing the only mutating command.
            if (!isEnabled()) return@withLock ProjectionConsentResult.DISABLED
            if (!isRayNeoAttached()) return@withLock ProjectionConsentResult.RAYNEO_NOT_ATTACHED

            val click = shell.exec(
                "/system/bin/input -d 0 tap ${target.centerX} ${target.centerY}",
                CLICK_TIMEOUT_MS,
            )
            return@withLock if (click.exitCode == 0) {
                lastConfirmedAtMs = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "confirmed exact ColorOS projection dialog reason=$reason at=(${target.centerX},${target.centerY})",
                )
                ProjectionConsentResult.CONFIRMED
            } else {
                Log.w(TAG, "projection confirm click failed reason=$reason exit=${click.exitCode}")
                ProjectionConsentResult.CLICK_FAILED
            }
        }

        ProjectionConsentResult.NOT_FOUND
    }

    private companion object {
        const val TAG = "ProjectionConsent"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 350L
        const val RECENT_CONFIRM_QUIET_MS = 1_500L
        const val DUMP_TIMEOUT_MS = 6_000L
        const val CLICK_TIMEOUT_MS = 3_000L
        const val UI_DUMP_PATH = "/data/local/tmp/ar_glass_plus_projection_consent.xml"
        const val DUMP_COMMAND =
            "/system/bin/rm -f $UI_DUMP_PATH; " +
                "/system/bin/uiautomator dump --compressed $UI_DUMP_PATH >/dev/null 2>&1 && " +
                "/system/bin/cat $UI_DUMP_PATH"
    }
}

/** Pure parser kept separate so the destructive boundary is host-testable. */
internal object ProjectionConsentDialogMatcher {
    data class ClickTarget(val centerX: Int, val centerY: Int)

    fun findPositiveButton(xml: String): ClickTarget? {
        if (!xml.contains("package=\"$SYSTEM_UI_PACKAGE\"")) return null

        val title = nodeByResourceId(xml, TITLE_RESOURCE_ID) ?: return null
        val message = nodeByResourceId(xml, MESSAGE_RESOURCE_ID) ?: return null
        val positive = nodeByResourceId(xml, POSITIVE_BUTTON_RESOURCE_ID) ?: return null

        if (attribute(title, "package") != SYSTEM_UI_PACKAGE) return null
        if (attribute(title, "text") != EXPECTED_TITLE) return null
        if (attribute(message, "package") != SYSTEM_UI_PACKAGE) return null
        if (attribute(message, "text") != EXPECTED_MESSAGE) return null
        if (attribute(positive, "package") != SYSTEM_UI_PACKAGE) return null
        if (attribute(positive, "text") != EXPECTED_POSITIVE_TEXT) return null
        if (attribute(positive, "clickable") != "true") return null
        if (attribute(positive, "enabled") != "true") return null

        val bounds = BOUNDS.find(attribute(positive, "bounds") ?: return null) ?: return null
        val left = bounds.groupValues[1].toIntOrNull() ?: return null
        val top = bounds.groupValues[2].toIntOrNull() ?: return null
        val right = bounds.groupValues[3].toIntOrNull() ?: return null
        val bottom = bounds.groupValues[4].toIntOrNull() ?: return null
        if (right <= left || bottom <= top) return null
        return ClickTarget((left + right) / 2, (top + bottom) / 2)
    }

    private fun nodeByResourceId(xml: String, resourceId: String): String? =
        NODE.findAll(xml).firstOrNull {
            attribute(it.value, "resource-id") == resourceId
        }?.value

    private fun attribute(node: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}="([^"]*)"""")
            .find(node)
            ?.groupValues
            ?.get(1)

    private val NODE = Regex("""<node\b[^>]*>""")
    private val BOUNDS = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val TITLE_RESOURCE_ID = "com.android.systemui:id/alertTitle"
    private const val MESSAGE_RESOURCE_ID = "android:id/message"
    private const val POSITIVE_BUTTON_RESOURCE_ID = "android:id/button1"
    private const val EXPECTED_TITLE = "是否开始投屏？"
    private const val EXPECTED_MESSAGE = "将本设备屏幕内容投射到外接显示屏"
    private const val EXPECTED_POSITIVE_TEXT = "开始"
}
