package com.example.ar_glass_plus.headpose.rayneo

import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RayNeoImuTest {
    @Test
    fun parsesVerifiedAir4ProImuFrameLayout() {
        val frame = ByteArray(64)
        frame[0] = 0x99.toByte()
        frame[1] = 0x65
        val bytes = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putFloat(4, 1.25f)
        bytes.putFloat(8, -2.5f)
        bytes.putFloat(12, 9.5f)
        bytes.putFloat(16, 12f)
        bytes.putFloat(20, -34f)
        bytes.putFloat(24, 56f)
        bytes.putFloat(28, 40f)
        bytes.putFloat(32, 96f)
        bytes.putFloat(36, -56f)
        bytes.putInt(40, 0x10203040)

        val sample = MutableRayNeoImuSample()
        assertTrue(RayNeoImuFrameParser.parse(frame, sample))
        assertEquals(1.25f, sample.accelX, 0f)
        assertEquals(-2.5f, sample.accelY, 0f)
        assertEquals(9.5f, sample.accelZ, 0f)
        assertEquals(12f, sample.gyroXDegreesPerSecond, 0f)
        assertEquals(-34f, sample.gyroYDegreesPerSecond, 0f)
        assertEquals(56f, sample.gyroZDegreesPerSecond, 0f)
        assertTrue(sample.magnetometerValid)
        assertEquals(40f, sample.magX, 0f)
        assertEquals(96f, sample.magY, 0f)
        assertEquals(-56f, sample.magZ, 0f)
        assertEquals(0x10203040, sample.deviceTick)

        frame[1] = 0x64
        assertFalse(RayNeoImuFrameParser.parse(frame, sample))

        frame[1] = 0x65
        bytes.putFloat(28, Float.NaN)
        assertTrue(RayNeoImuFrameParser.parse(frame, sample))
        assertFalse(sample.magnetometerValid)
    }

    @Test
    fun calibratedYawIntegratesAtMeasuredTickScaleAndRecenterZerosPose() {
        val fusion = RayNeoOrientationFusion(requiredStillSamples = 4)
        val sample = MutableRayNeoImuSample().apply {
            accelY = 9.81f
            gyroYDegreesPerSecond = 0.1f
        }
        var timestamp = 1_000_000_000L
        repeat(4) { index ->
            sample.deviceTick = index * 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }
        assertTrue(fusion.tracking)

        sample.gyroYDegreesPerSecond = 90.1f
        repeat(500) {
            sample.deviceTick += 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }

        val forward = Quat(
            fusion.outputX,
            fusion.outputY,
            fusion.outputZ,
            fusion.outputW,
        ).rotate(Vec3(0f, 0f, -1f))
        assertEquals(-1f, forward.x, 0.02f)
        assertEquals(0f, forward.y, 0.02f)
        assertEquals(0f, forward.z, 0.02f)

        fusion.recenter()
        assertEquals(0f, fusion.outputX, 1e-5f)
        assertEquals(0f, fusion.outputY, 1e-5f)
        assertEquals(0f, fusion.outputZ, 1e-5f)
        assertEquals(1f, fusion.outputW, 1e-5f)
    }

    @Test
    fun magneticHeadingBoundsStationaryYawDrift() {
        val fusion = RayNeoOrientationFusion(requiredStillSamples = 4)
        val sample = MutableRayNeoImuSample().apply {
            accelY = 9.81f
            magX = 40f
            magY = 96f
            magZ = -56f
            magnetometerValid = true
        }
        var timestamp = 1_000_000_000L
        repeat(4) { index ->
            sample.deviceTick = index * 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }
        assertTrue(fusion.magneticYawActive)

        sample.gyroYDegreesPerSecond = 10f
        repeat(5_000) {
            sample.deviceTick += 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }

        val forward = Quat(
            fusion.outputX,
            fusion.outputY,
            fusion.outputZ,
            fusion.outputW,
        ).rotate(Vec3(0f, 0f, -1f))
        assertTrue(fusion.magneticYawActive)
        assertEquals(0f, forward.x, 0.15f)
        assertTrue(forward.z < -0.98f)
    }

    @Test
    fun magneticReferenceTracksRealYawInsteadOfResistingMotion() {
        val fusion = RayNeoOrientationFusion(requiredStillSamples = 4)
        val sample = MutableRayNeoImuSample().apply {
            accelY = 9.81f
            magZ = -50f
            magnetometerValid = true
        }
        var timestamp = 1_000_000_000L
        repeat(4) { index ->
            sample.deviceTick = index * 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }

        sample.gyroYDegreesPerSecond = 90f
        repeat(500) { index ->
            val yawRadians = Math.toRadians((index + 1) * 0.18)
            sample.magX = (kotlin.math.sin(yawRadians) * 50.0).toFloat()
            sample.magZ = (-kotlin.math.cos(yawRadians) * 50.0).toFloat()
            sample.deviceTick += 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }

        val forward = Quat(
            fusion.outputX,
            fusion.outputY,
            fusion.outputZ,
            fusion.outputW,
        ).rotate(Vec3(0f, 0f, -1f))
        assertTrue(fusion.magneticYawActive)
        assertEquals(-1f, forward.x, 0.02f)
        assertEquals(0f, forward.z, 0.02f)
    }

    @Test
    fun disturbedMagneticFieldIsRejectedWithoutStoppingTracking() {
        val fusion = RayNeoOrientationFusion(requiredStillSamples = 4)
        val sample = MutableRayNeoImuSample().apply {
            accelY = 9.81f
            magZ = -50f
            magnetometerValid = true
        }
        var timestamp = 1_000_000_000L
        repeat(4) { index ->
            sample.deviceTick = index * 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }

        sample.magZ = -500f
        repeat(50) {
            sample.deviceTick += 20
            timestamp += 2_000_000L
            fusion.update(sample, timestamp)
        }

        assertTrue(fusion.tracking)
        assertFalse(fusion.magneticYawActive)
        assertTrue(fusion.outputX.isFinite())
        assertTrue(fusion.outputY.isFinite())
        assertTrue(fusion.outputZ.isFinite())
        assertTrue(fusion.outputW.isFinite())
    }
}
