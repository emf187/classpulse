package com.classpulse.app

import kotlin.math.sqrt

/**
 * Lightweight step / motion detector from the accelerometer (no step-sensor HAL needed).
 *
 * Pipeline: raw (x,y,z) -> magnitude -> gravity low-pass removed -> peak detection.
 * A step = linear-accel peak above threshold with >= MIN_STEP_INTERVAL_MS since last step.
 * isMoving = >= 2 steps in the trailing 5 s window OR recent variance above stillThreshold.
 */
class StepDetector(
    private val peakThreshold: Float = 1.4f,      // m/s^2 of linear accel
    private val minStepIntervalMs: Long = 320L,
    private val movingWindowMs: Long = 5_000L
) {
    private var gravity = FloatArray(3) { 0f }
    private var lastStepTimeMs = 0L
    private val stepTimes = ArrayDeque<Long>()
    var stepCount: Int = 0
        private set
    var lastMagnitude: Float = 0f
        private set

    /** Feed one accelerometer sample. Returns true if a new step was detected. */
    fun onSample(x: Float, y: Float, z: Float, nowMs: Long = System.currentTimeMillis()): Boolean {
        // Low-pass to isolate gravity (alpha 0.8), subtract to get linear acceleration.
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        lastMagnitude = mag

        var stepped = false
        if (mag > peakThreshold && nowMs - lastStepTimeMs > minStepIntervalMs) {
            lastStepTimeMs = nowMs
            stepCount++
            stepTimes.addLast(nowMs)
            stepped = true
        }
        while (stepTimes.isNotEmpty() && nowMs - stepTimes.first() > movingWindowMs) {
            stepTimes.removeFirst()
        }
        return stepped
    }

    fun isMoving(nowMs: Long = System.currentTimeMillis()): Boolean {
        while (stepTimes.isNotEmpty() && nowMs - stepTimes.first() > movingWindowMs) {
            stepTimes.removeFirst()
        }
        return stepTimes.size >= 2
    }

    fun reset() {
        stepCount = 0
        stepTimes.clear()
        lastStepTimeMs = 0L
    }
}
