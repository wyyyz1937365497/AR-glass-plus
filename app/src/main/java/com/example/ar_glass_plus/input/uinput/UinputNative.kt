package com.example.ar_glass_plus.input.uinput

/**
 * JNI bridge to /dev/uinput (virtual REL mouse). Loaded inside the root
 * service process. Adapted from pgratz1/AR-Touchpad (Apache-2.0).
 */
object UinputNative {

    init {
        try {
            System.loadLibrary("ar_glass_uinput")
        } catch (e: UnsatisfiedLinkError) {
            // Root service process may lack the standard library search path;
            // fall back to the APK-embedded lib.
            System.load("${apkBasePath()}/lib/arm64-v8a/libar_glass_uinput.so")
        }
    }

    external fun nOpen(): Int
    external fun nIoctl(request: Int, value: Int): Int
    external fun nWriteDevInfo(name: String): Int
    external fun nWriteEvent(type: Int, code: Int, value: Int): Int
    external fun nClose()

    private fun apkBasePath(): String =
        UinputNative::class.java.protectionDomain?.codeSource?.location?.path
            ?.removeSuffix("/base.apk") ?: ""
}
