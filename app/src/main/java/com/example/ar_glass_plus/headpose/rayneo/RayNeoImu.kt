package com.example.ar_glass_plus.headpose.rayneo

import kotlin.math.sqrt

internal class MutableRayNeoImuSample {
    var accelX = 0f
    var accelY = 0f
    var accelZ = 0f
    var gyroXDegreesPerSecond = 0f
    var gyroYDegreesPerSecond = 0f
    var gyroZDegreesPerSecond = 0f
    var magX = 0f
    var magY = 0f
    var magZ = 0f
    var magnetometerValid = false
    var deviceTick = 0
}

/** Parser for the Air 4 Pro 64-byte 0x99/0x65 interrupt report. */
internal object RayNeoImuFrameParser {
    const val FRAME_BYTES = 64

    fun parse(frame: ByteArray, sample: MutableRayNeoImuSample): Boolean {
        if (frame.size < FRAME_BYTES || u8(frame[0]) != 0x99 || u8(frame[1]) != 0x65) {
            return false
        }
        val ax = readFloatLe(frame, 4)
        val ay = readFloatLe(frame, 8)
        val az = readFloatLe(frame, 12)
        val gx = readFloatLe(frame, 16)
        val gy = readFloatLe(frame, 20)
        val gz = readFloatLe(frame, 24)
        val mx = readFloatLe(frame, 28)
        val my = readFloatLe(frame, 32)
        val mz = readFloatLe(frame, 36)
        val tick = readIntLe(frame, 40)
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite() ||
            !gx.isFinite() || !gy.isFinite() || !gz.isFinite()
        ) {
            return false
        }
        sample.accelX = ax
        sample.accelY = ay
        sample.accelZ = az
        sample.gyroXDegreesPerSecond = gx
        sample.gyroYDegreesPerSecond = gy
        sample.gyroZDegreesPerSecond = gz
        sample.magnetometerValid = mx.isFinite() && my.isFinite() && mz.isFinite()
        sample.magX = if (sample.magnetometerValid) mx else 0f
        sample.magY = if (sample.magnetometerValid) my else 0f
        sample.magZ = if (sample.magnetometerValid) mz else 0f
        sample.deviceTick = tick
        return true
    }

    private fun readFloatLe(bytes: ByteArray, offset: Int): Float =
        Float.fromBits(readIntLe(bytes, offset))

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        u8(bytes[offset]) or
            (u8(bytes[offset + 1]) shl 8) or
            (u8(bytes[offset + 2]) shl 16) or
            (u8(bytes[offset + 3]) shl 24)

    private fun u8(value: Byte): Int = value.toInt() and 0xff
}

/**
 * Allocation-free 3DoF fusion for RayNeo gyro, accelerometer and magnetometer reports.
 *
 * The absolute quaternion maps sensor-local vectors into a gravity-levelled
 * world. A saved reference quaternion removes mounting angle and establishes
 * the wearer's current forward direction. The magnetometer anchors yaw to the
 * field observed during still calibration; it is disturbance-rejected and is
 * deliberately not exposed as an absolute compass heading.
 */
internal class RayNeoOrientationFusion(
    private val requiredStillSamples: Int = DEFAULT_STILL_SAMPLES,
    private val gravityCorrectionGain: Float = DEFAULT_GRAVITY_GAIN,
    private val magneticCorrectionGain: Float = DEFAULT_MAGNETIC_GAIN,
) {
    private var biasSamples = 0
    private var biasSumX = 0f
    private var biasSumY = 0f
    private var biasSumZ = 0f
    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f

    private var magneticSamples = 0
    private var magneticSumX = 0f
    private var magneticSumY = 0f
    private var magneticSumZ = 0f
    private var magneticNormSum = 0f
    private var magneticReferenceX = 0f
    private var magneticReferenceY = 0f
    private var magneticReferenceZ = 0f
    private var magneticReferenceStrength = 0f
    private var magneticReferenceReady = false
    private var rejectedMagneticSamples = 0

    private var qx = 0f
    private var qy = 0f
    private var qz = 0f
    private var qw = 1f
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    private var refW = 1f

    var outputX = 0f
        private set
    var outputY = 0f
        private set
    var outputZ = 0f
        private set
    var outputW = 1f
        private set

    var tracking = false
        private set
    var magneticYawActive = false
        private set
    var magneticFieldStrength = 0f
        private set

    private var lastDeviceTick = 0
    private var lastSampleTimeNanos = 0L

    fun reset() {
        resetBiasCalibration()
        biasX = 0f
        biasY = 0f
        biasZ = 0f
        magneticReferenceX = 0f
        magneticReferenceY = 0f
        magneticReferenceZ = 0f
        magneticReferenceStrength = 0f
        magneticReferenceReady = false
        rejectedMagneticSamples = 0
        qx = 0f
        qy = 0f
        qz = 0f
        qw = 1f
        refX = 0f
        refY = 0f
        refZ = 0f
        refW = 1f
        outputX = 0f
        outputY = 0f
        outputZ = 0f
        outputW = 1f
        tracking = false
        magneticYawActive = false
        magneticFieldStrength = 0f
        lastDeviceTick = 0
        lastSampleTimeNanos = 0L
    }

    /** Returns true once a calibrated orientation is available. */
    fun update(sample: MutableRayNeoImuSample, sampleTimeNanos: Long): Boolean {
        val accelerationNorm = sqrt(
            sample.accelX * sample.accelX +
                sample.accelY * sample.accelY +
                sample.accelZ * sample.accelZ,
        )
        val rawGyroNorm = sqrt(
            sample.gyroXDegreesPerSecond * sample.gyroXDegreesPerSecond +
                sample.gyroYDegreesPerSecond * sample.gyroYDegreesPerSecond +
                sample.gyroZDegreesPerSecond * sample.gyroZDegreesPerSecond,
        )
        val magneticNorm = if (sample.magnetometerValid) {
            sqrt(sample.magX * sample.magX + sample.magY * sample.magY + sample.magZ * sample.magZ)
        } else {
            0f
        }
        magneticFieldStrength = if (magneticNorm.isFinite()) magneticNorm else 0f

        if (!tracking) {
            if (accelerationNorm !in MIN_GRAVITY..MAX_GRAVITY || rawGyroNorm > MAX_STILL_GYRO_DPS) {
                resetBiasCalibration()
                return false
            }
            biasSumX += sample.gyroXDegreesPerSecond
            biasSumY += sample.gyroYDegreesPerSecond
            biasSumZ += sample.gyroZDegreesPerSecond
            biasSamples++
            if (isUsableMagneticNorm(magneticNorm)) {
                magneticSumX += sample.magX
                magneticSumY += sample.magY
                magneticSumZ += sample.magZ
                magneticNormSum += magneticNorm
                magneticSamples++
            }
            if (biasSamples < requiredStillSamples) return false

            biasX = biasSumX / biasSamples
            biasY = biasSumY / biasSamples
            biasZ = biasSumZ / biasSamples
            val upX = sample.accelX / accelerationNorm
            val upY = sample.accelY / accelerationNorm
            val upZ = sample.accelZ / accelerationNorm
            initializeFromGravity(upX, upY, upZ)
            initializeMagneticReference(upX, upY, upZ)
            refX = qx
            refY = qy
            refZ = qz
            refW = qw
            lastDeviceTick = sample.deviceTick
            lastSampleTimeNanos = sampleTimeNanos
            tracking = true
            updateRelativeOrientation()
            return true
        }

        val tickDelta = (sample.deviceTick - lastDeviceTick).toUInt().toLong()
        var dtSeconds = tickDelta / TICK_UNITS_PER_SECOND
        if (tickDelta == 0L || dtSeconds > MAX_SAMPLE_INTERVAL_SECONDS) {
            dtSeconds = ((sampleTimeNanos - lastSampleTimeNanos).coerceAtLeast(0L) / 1_000_000_000.0)
                .coerceAtMost(MAX_SAMPLE_INTERVAL_SECONDS.toDouble())
                .toFloat()
        }
        lastDeviceTick = sample.deviceTick
        lastSampleTimeNanos = sampleTimeNanos
        if (dtSeconds <= 0f) return true

        var gx = (sample.gyroXDegreesPerSecond - biasX) * DEGREES_TO_RADIANS
        var gy = (sample.gyroYDegreesPerSecond - biasY) * DEGREES_TO_RADIANS
        var gz = (sample.gyroZDegreesPerSecond - biasZ) * DEGREES_TO_RADIANS

        // World +Y expressed in sensor/body coordinates: R(q)^T * (0,1,0).
        val estimatedUpX = 2f * (qx * qy + qw * qz)
        val estimatedUpY = 1f - 2f * (qx * qx + qz * qz)
        val estimatedUpZ = 2f * (qy * qz - qw * qx)

        if (accelerationNorm in MIN_GRAVITY..MAX_GRAVITY) {
            val measuredX = sample.accelX / accelerationNorm
            val measuredY = sample.accelY / accelerationNorm
            val measuredZ = sample.accelZ / accelerationNorm

            // Body-space correction that rotates estimated gravity toward measured gravity.
            val errorX = measuredY * estimatedUpZ - measuredZ * estimatedUpY
            val errorY = measuredZ * estimatedUpX - measuredX * estimatedUpZ
            val errorZ = measuredX * estimatedUpY - measuredY * estimatedUpX
            gx += gravityCorrectionGain * errorX
            gy += gravityCorrectionGain * errorY
            gz += gravityCorrectionGain * errorZ
        }

        val magneticCorrection = magneticYawCorrection(
            sample,
            magneticNorm,
            estimatedUpX,
            estimatedUpY,
            estimatedUpZ,
        )
        gx += magneticCorrection * estimatedUpX
        gy += magneticCorrection * estimatedUpY
        gz += magneticCorrection * estimatedUpZ

        integrateLocalAngularVelocity(gx, gy, gz, dtSeconds)
        updateRelativeOrientation()
        return true
    }

    fun recenter() {
        if (!tracking) return
        refX = qx
        refY = qy
        refZ = qz
        refW = qw
        updateRelativeOrientation()
    }

    private fun resetBiasCalibration() {
        biasSamples = 0
        biasSumX = 0f
        biasSumY = 0f
        biasSumZ = 0f
        magneticSamples = 0
        magneticSumX = 0f
        magneticSumY = 0f
        magneticSumZ = 0f
        magneticNormSum = 0f
    }

    /** Quaternion rotating the measured sensor-space up vector onto world +Y. */
    private fun initializeFromGravity(ax: Float, ay: Float, az: Float) {
        if (ay < -0.9999f) {
            qx = 1f
            qy = 0f
            qz = 0f
            qw = 0f
            return
        }
        qx = -az
        qy = 0f
        qz = ax
        qw = 1f + ay
        normalizeAbsolute()
    }

    /**
     * Captures a levelled, normalized field direction as the relative yaw
     * reference. Requiring most calibration frames prevents a transient or
     * unsupported sensor from silently becoming the heading authority.
     */
    private fun initializeMagneticReference(upX: Float, upY: Float, upZ: Float) {
        magneticReferenceReady = false
        magneticYawActive = false
        if (magneticSamples * MIN_MAGNETIC_SAMPLE_DENOMINATOR <
            requiredStillSamples * MIN_MAGNETIC_SAMPLE_NUMERATOR
        ) {
            return
        }
        val inverseCount = 1f / magneticSamples
        var horizontalX = magneticSumX * inverseCount
        var horizontalY = magneticSumY * inverseCount
        var horizontalZ = magneticSumZ * inverseCount
        val projection = horizontalX * upX + horizontalY * upY + horizontalZ * upZ
        horizontalX -= projection * upX
        horizontalY -= projection * upY
        horizontalZ -= projection * upZ
        val horizontalNorm = sqrt(
            horizontalX * horizontalX + horizontalY * horizontalY + horizontalZ * horizontalZ,
        )
        if (horizontalNorm < MIN_HORIZONTAL_FIELD) return
        horizontalX /= horizontalNorm
        horizontalY /= horizontalNorm
        horizontalZ /= horizontalNorm

        // R(q) * measuredBodyDirection -> gravity-levelled world reference.
        val worldX =
            (1f - 2f * (qy * qy + qz * qz)) * horizontalX +
                2f * (qx * qy - qw * qz) * horizontalY +
                2f * (qx * qz + qw * qy) * horizontalZ
        val worldY =
            2f * (qx * qy + qw * qz) * horizontalX +
                (1f - 2f * (qx * qx + qz * qz)) * horizontalY +
                2f * (qy * qz - qw * qx) * horizontalZ
        val worldZ =
            2f * (qx * qz - qw * qy) * horizontalX +
                2f * (qy * qz + qw * qx) * horizontalY +
                (1f - 2f * (qx * qx + qy * qy)) * horizontalZ
        val worldNorm = sqrt(worldX * worldX + worldY * worldY + worldZ * worldZ)
        if (worldNorm <= 0f) return
        magneticReferenceX = worldX / worldNorm
        magneticReferenceY = worldY / worldNorm
        magneticReferenceZ = worldZ / worldNorm
        magneticReferenceStrength = magneticNormSum * inverseCount
        magneticReferenceReady = isUsableMagneticNorm(magneticReferenceStrength)
        magneticYawActive = magneticReferenceReady && magneticCorrectionGain > 0f
        rejectedMagneticSamples = 0
    }

    /**
     * Returns a bounded body-space angular correction around estimated up.
     * Field-strength and horizontal-component gates reject nearby magnets and
     * degenerate headings without interrupting gyro/accelerometer tracking.
     */
    private fun magneticYawCorrection(
        sample: MutableRayNeoImuSample,
        magneticNorm: Float,
        upX: Float,
        upY: Float,
        upZ: Float,
    ): Float {
        if (!magneticReferenceReady || magneticCorrectionGain <= 0f ||
            !isUsableMagneticNorm(magneticNorm) ||
            magneticNorm !in
            magneticReferenceStrength * MIN_FIELD_RATIO..magneticReferenceStrength * MAX_FIELD_RATIO
        ) {
            rejectMagneticSample()
            return 0f
        }

        var measuredX = sample.magX / magneticNorm
        var measuredY = sample.magY / magneticNorm
        var measuredZ = sample.magZ / magneticNorm
        val measuredVertical = measuredX * upX + measuredY * upY + measuredZ * upZ
        measuredX -= measuredVertical * upX
        measuredY -= measuredVertical * upY
        measuredZ -= measuredVertical * upZ
        val measuredHorizontalNorm = sqrt(
            measuredX * measuredX + measuredY * measuredY + measuredZ * measuredZ,
        )
        if (measuredHorizontalNorm < MIN_NORMALIZED_HORIZONTAL_FIELD) {
            rejectMagneticSample()
            return 0f
        }
        measuredX /= measuredHorizontalNorm
        measuredY /= measuredHorizontalNorm
        measuredZ /= measuredHorizontalNorm

        // R(q)^T * referenceWorld -> expected direction in sensor/body space.
        var expectedX =
            (1f - 2f * (qy * qy + qz * qz)) * magneticReferenceX +
                2f * (qx * qy + qw * qz) * magneticReferenceY +
                2f * (qx * qz - qw * qy) * magneticReferenceZ
        var expectedY =
            2f * (qx * qy - qw * qz) * magneticReferenceX +
                (1f - 2f * (qx * qx + qz * qz)) * magneticReferenceY +
                2f * (qy * qz + qw * qx) * magneticReferenceZ
        var expectedZ =
            2f * (qx * qz + qw * qy) * magneticReferenceX +
                2f * (qy * qz - qw * qx) * magneticReferenceY +
                (1f - 2f * (qx * qx + qy * qy)) * magneticReferenceZ
        val expectedVertical = expectedX * upX + expectedY * upY + expectedZ * upZ
        expectedX -= expectedVertical * upX
        expectedY -= expectedVertical * upY
        expectedZ -= expectedVertical * upZ
        val expectedNorm = sqrt(expectedX * expectedX + expectedY * expectedY + expectedZ * expectedZ)
        if (expectedNorm < MIN_NORMALIZED_HORIZONTAL_FIELD) {
            rejectMagneticSample()
            return 0f
        }
        expectedX /= expectedNorm
        expectedY /= expectedNorm
        expectedZ /= expectedNorm

        val crossX = measuredY * expectedZ - measuredZ * expectedY
        val crossY = measuredZ * expectedX - measuredX * expectedZ
        val crossZ = measuredX * expectedY - measuredY * expectedX
        val yawError = crossX * upX + crossY * upY + crossZ * upZ
        rejectedMagneticSamples = 0
        magneticYawActive = true
        return (magneticCorrectionGain * yawError).coerceIn(
            -MAX_MAGNETIC_CORRECTION_RADIANS,
            MAX_MAGNETIC_CORRECTION_RADIANS,
        )
    }

    private fun rejectMagneticSample() {
        if (rejectedMagneticSamples < MAGNETIC_REJECTION_GRACE_SAMPLES) {
            rejectedMagneticSamples++
        }
        if (rejectedMagneticSamples >= MAGNETIC_REJECTION_GRACE_SAMPLES) {
            magneticYawActive = false
        }
    }

    private fun isUsableMagneticNorm(norm: Float): Boolean =
        norm.isFinite() && norm in MIN_MAGNETIC_FIELD..MAX_MAGNETIC_FIELD

    /** q += 0.5 * q * omega(local) * dt. */
    private fun integrateLocalAngularVelocity(gx: Float, gy: Float, gz: Float, dt: Float) {
        val halfDt = 0.5f * dt
        val dx = (qw * gx + qy * gz - qz * gy) * halfDt
        val dy = (qw * gy - qx * gz + qz * gx) * halfDt
        val dz = (qw * gz + qx * gy - qy * gx) * halfDt
        val dw = (-qx * gx - qy * gy - qz * gz) * halfDt
        qx += dx
        qy += dy
        qz += dz
        qw += dw
        normalizeAbsolute()
    }

    /** Relative object/camera orientation: current absolute * reference inverse. */
    private fun updateRelativeOrientation() {
        outputX = -qw * refX + qx * refW - qy * refZ + qz * refY
        outputY = -qw * refY + qx * refZ + qy * refW - qz * refX
        outputZ = -qw * refZ - qx * refY + qy * refX + qz * refW
        outputW = qw * refW + qx * refX + qy * refY + qz * refZ
        val norm = sqrt(
            outputX * outputX + outputY * outputY + outputZ * outputZ + outputW * outputW,
        )
        if (norm > 0f) {
            outputX /= norm
            outputY /= norm
            outputZ /= norm
            outputW /= norm
        }
    }

    private fun normalizeAbsolute() {
        val norm = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (norm <= 0f) {
            qx = 0f
            qy = 0f
            qz = 0f
            qw = 1f
            return
        }
        qx /= norm
        qy /= norm
        qz /= norm
        qw /= norm
    }

    private companion object {
        const val DEFAULT_STILL_SAMPLES = 400
        const val DEFAULT_GRAVITY_GAIN = 2f
        const val DEFAULT_MAGNETIC_GAIN = 2f
        const val MIN_GRAVITY = 6f
        const val MAX_GRAVITY = 13f
        const val MAX_STILL_GYRO_DPS = 2f
        const val MIN_MAGNETIC_FIELD = 1f
        const val MAX_MAGNETIC_FIELD = 2_000f
        const val MIN_FIELD_RATIO = 0.7f
        const val MAX_FIELD_RATIO = 1.3f
        const val MIN_HORIZONTAL_FIELD = 1f
        const val MIN_NORMALIZED_HORIZONTAL_FIELD = 0.15f
        const val MIN_MAGNETIC_SAMPLE_NUMERATOR = 3
        const val MIN_MAGNETIC_SAMPLE_DENOMINATOR = 4
        const val MAGNETIC_REJECTION_GRACE_SAMPLES = 50
        const val MAX_MAGNETIC_CORRECTION_RADIANS = 0.35f
        const val TICK_UNITS_PER_SECOND = 10_000f
        const val MAX_SAMPLE_INTERVAL_SECONDS = 0.02f
        const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
    }
}
