package com.example.ar_glass_plus.display.sbs

import android.os.Process
import android.util.Log
import com.example.ar_glass_plus.root.RootShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface SbsKernelState {
    data object Inactive : SbsKernelState

    data class Transitioning(val action: String) : SbsKernelState

    data class Active(val detail: String) : SbsKernelState

    data class Error(val message: String) : SbsKernelState
}

sealed interface SbsOperationResult {
    data class Success(val detail: String) : SbsOperationResult

    data class Failure(val message: String, val exitCode: Int) : SbsOperationResult
}

enum class SbsReleaseReason(val wireValue: String) {
    USER_SELECTED_2D("user-2d"),
    OUTPUT_DISCONNECTED("output-disconnected"),
    APP_BACKGROUNDED("app-backgrounded"),
}

interface SbsKernelPort {
    val state: StateFlow<SbsKernelState>

    suspend fun acquire(): SbsOperationResult

    suspend fun release(reason: SbsReleaseReason): SbsOperationResult
}

/**
 * Thin app-side client for the SukiSU module's root transaction controller.
 *
 * The app never calls insmod/rmmod or writes HID directly. A random per-process
 * lease token prevents a stale Activity/process from releasing a newer owner,
 * while the module watchdog handles process death.
 */
class SbsKernelController(
    private val shell: RootShell,
    private val pidProvider: () -> Int = { Process.myPid() },
    private val leaseToken: String = UUID.randomUUID().toString(),
) : SbsKernelPort {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SbsKernelState>(SbsKernelState.Inactive)
    private var ownsLease = false

    override val state: StateFlow<SbsKernelState> = _state.asStateFlow()

    override suspend fun acquire(): SbsOperationResult = mutex.withLock {
        if (ownsLease && _state.value is SbsKernelState.Active) {
            return@withLock SbsOperationResult.Success("already-active")
        }

        _state.value = SbsKernelState.Transitioning("activating")
        val result = shell.exec(
            "$CONTROLLER_PATH acquire ${pidProvider()} $leaseToken",
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        val detail = commandDetail(result.stdout, result.stderr)
        if (result.exitCode == 0 && result.stdout.lineSequence().any { it.startsWith("state=active") }) {
            ownsLease = true
            _state.value = SbsKernelState.Active(detail)
            Log.i(TAG, "SBS kernel lease acquired: $detail")
            SbsOperationResult.Success(detail)
        } else {
            ownsLease = false
            val message = detail.ifBlank { "module controller exit=${result.exitCode}" }
            _state.value = SbsKernelState.Error(message)
            Log.w(TAG, "SBS kernel acquire failed: exit=${result.exitCode} $message")
            SbsOperationResult.Failure(message, result.exitCode)
        }
    }

    override suspend fun release(reason: SbsReleaseReason): SbsOperationResult = mutex.withLock {
        if (!ownsLease) {
            _state.value = SbsKernelState.Inactive
            return@withLock SbsOperationResult.Success("not-owned")
        }

        _state.value = SbsKernelState.Transitioning("releasing")
        val result = shell.exec(
            "$CONTROLLER_PATH release $leaseToken ${reason.wireValue}",
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        val detail = commandDetail(result.stdout, result.stderr)
        val released = result.stdout.lineSequence().any { it.startsWith("state=inactive") }
        if (released) ownsLease = false

        if (result.exitCode == 0 && released) {
            _state.value = SbsKernelState.Inactive
            Log.i(TAG, "SBS kernel lease released: ${reason.wireValue}")
            SbsOperationResult.Success(detail)
        } else {
            val message = detail.ifBlank { "module controller exit=${result.exitCode}" }
            _state.value = SbsKernelState.Error(message)
            Log.w(TAG, "SBS kernel release failed: exit=${result.exitCode} $message")
            SbsOperationResult.Failure(message, result.exitCode)
        }
    }

    private fun commandDetail(stdout: String, stderr: String): String =
        sequenceOf(stdout.trim(), stderr.trim())
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .replace('\n', ' ')
            .take(MAX_DETAIL_CHARS)

    private companion object {
        const val TAG = "SbsKernelCtrl"
        const val CONTROLLER_PATH =
            "/data/adb/modules/ar_glass_plus_dpfix/bin/ar-glass-dpctl"
        const val COMMAND_TIMEOUT_MS = 20_000L
        const val MAX_DETAIL_CHARS = 320
    }
}
