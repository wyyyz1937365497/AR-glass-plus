package com.example.ar_glass_plus.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Result of a single root shell command. */
data class RootResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Minimal root shell over `su -c`. All privileged operations go through this
 * interface so the backend can later be swapped for a persistent root daemon
 * without touching callers.
 */
interface RootShell {
    /** True when `su` returns a uid=0 shell. */
    suspend fun isAvailable(): Boolean

    /**
     * Run one command via `su -c`. Never on the main thread, always bounded
     * by [timeoutMs]; stdout/stderr are captured separately.
     */
    suspend fun exec(command: String, timeoutMs: Long = 5_000): RootResult
}

class RootShellImpl : RootShell {

    override suspend fun isAvailable(): Boolean {
        val r = exec("id")
        return r.exitCode == 0 && r.stdout.contains("uid=0")
    }

    override suspend fun exec(command: String, timeoutMs: Long): RootResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "exec: ${sanitize(command)}")
            val process = try {
                // Android app processes do not inherit adb shell's PATH. On
                // this SukiSU target the injected executable is exposed at
                // /system/bin/su, so use the absolute path instead of relying
                // on environment-dependent command lookup.
                ProcessBuilder(SU_PATH, "-c", command)
                    .redirectErrorStream(false)
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "failed to start su: ${e.message}")
                return@withContext RootResult(-1, "", "failed to start su: ${e.message}")
            }

            // Drain both streams concurrently — blocking one pipe deadlocks the child.
            val stdoutFuture = CompletableFuture<String>()
            val stderrFuture = CompletableFuture<String>()
            Thread { stdoutFuture.complete(process.inputStream.bufferedReader().readText()) }.start()
            Thread { stderrFuture.complete(process.errorStream.bufferedReader().readText()) }.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "timed out after ${timeoutMs}ms: ${sanitize(command)}")
                process.destroy()
                process.waitFor()
            }

            val stdout = try { stdoutFuture.get(2, TimeUnit.SECONDS) } catch (e: Exception) { "" }
            val stderr = try { stderrFuture.get(2, TimeUnit.SECONDS) } catch (e: Exception) { "" }
            val code = try { process.exitValue() } catch (e: IllegalThreadStateException) { -1 }

            if (code != 0 || stderr.isNotBlank()) {
                Log.d(TAG, "result code=$code stderr=${stderr.trim().take(200)}")
            }
            RootResult(code, stdout, stderr)
        }

    /** Redact obvious secrets before logging. */
    private fun sanitize(command: String): String =
        command.replace(
            Regex("(?i)(token|key|secret|password|apikey|api_key)([=\\s]+)\\S+"),
            "$1$2***",
        )

    private companion object {
        const val TAG = "RootShell"
        const val SU_PATH = "/system/bin/su"
    }
}
