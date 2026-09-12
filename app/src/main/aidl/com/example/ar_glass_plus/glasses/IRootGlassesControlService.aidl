package com.example.ar_glass_plus.glasses;

/** Root-side HID control channel for the RayNeo Air 4 Pro vendor report. */
interface IRootGlassesControlService {
    /** Resolves the Air 4 Pro hidraw node; empty string when not connected. */
    String acquireHidraw();

    /**
     * Sends one vendor report: [0x66, cmd, value, payload...].
     * Returns the number of bytes written, or -1 on failure.
     */
    int sendCommand(int cmd, int value, in byte[] payload);

    void destroy();
}
