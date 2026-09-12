package com.example.ar_glass_plus.headpose.rayneo;

interface IRootHeadPoseService {
    // Starts the Air 4 Pro hidraw stream and initial still-bias calibration.
    boolean startTracking() = 1;

    // [state, timestampNanos, sampleCount, qx, qy, qz, qw, deviceTick].
    double[] getLatestPose() = 2;

    // Makes the current fused orientation the new forward direction.
    void recenter() = 3;

    // Sends IMU-off and closes hidraw. Safe to call repeatedly.
    void stopTracking() = 4;

    void destroy() = 16777114;
}
