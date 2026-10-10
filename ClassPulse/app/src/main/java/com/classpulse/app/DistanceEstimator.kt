package com.classpulse.app

import kotlin.math.pow

/**
 * Converts BLE RSSI to an estimated distance in metres using the
 * log-distance path-loss model, with exponential smoothing.
 *
 * d = 10 ^ ((TxPower - RSSI) / (10 * n))
 *
 * TxPower = RSSI measured at 1 m (typical phone: -59 dBm).
 * n = path-loss exponent (2.0 = open classroom; 2.5-3.0 = concrete/walls).
 *
 * The accelerometer gates updates: when the phone is still, the estimate is
 * frozen/strongly smoothed to stop RSSI jitter from faking movement.
 */
class DistanceEstimator(
    val txPower: Int = TX_POWER_DEFAULT,
    val pathLossN: Double = PATH_LOSS_N_DEFAULT,
    private val alpha: Double = 0.35
) {
    private var smoothedDistance: Double? = null

    /** Raw (unsmoothed) estimate for a single RSSI reading. Clamped to [0.1, 20] m. */
    fun rawDistance(rssi: Int): Double {
        val d = 10.0.pow((txPower - rssi) / (10.0 * pathLossN))
        return d.coerceIn(0.1, 20.0)
    }

    /**
     * Filtered estimate.
     * @param isMoving from [StepDetector]: if false, smoothing is much stronger.
     */
    fun update(rssi: Int, isMoving: Boolean): Double {
        val raw = rawDistance(rssi)
        val a = if (isMoving) alpha else 0.12
        val prev = smoothedDistance
        smoothedDistance = if (prev == null) raw else a * raw + (1 - a) * prev
        return smoothedDistance!!
    }

    fun currentOrNull(): Double? = smoothedDistance

    fun reset() { smoothedDistance = null }

    companion object {
        const val TX_POWER_DEFAULT = -59
        const val PATH_LOSS_N_DEFAULT = 2.0
        const val NEAR_RADIUS_M = 1.0
        const val ROOM_RADIUS_M = 8.0
        const val OUT_TIMEOUT_MS = 15_000L

        fun classify(distanceM: Double?, lastSeenAgoMs: Long): PeerStatus {
            if (distanceM == null || lastSeenAgoMs > OUT_TIMEOUT_MS) return PeerStatus.OUT_OF_BOUNDS
            return when {
                distanceM <= NEAR_RADIUS_M -> PeerStatus.NEAR
                distanceM <= ROOM_RADIUS_M -> PeerStatus.IN_ROOM
                else -> PeerStatus.EDGE
            }
        }
    }
}

enum class PeerStatus { NEAR, IN_ROOM, EDGE, OUT_OF_BOUNDS }
