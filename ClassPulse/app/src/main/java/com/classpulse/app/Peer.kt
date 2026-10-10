package com.classpulse.app

/** One device in the classroom roster (self or a BLE peer). */
data class Peer(
    val deviceId: String,
    var displayName: String,
    var rssi: Int = -70,
    var distanceM: Double? = null,
    var steps: Int = 0,
    var lastSeenMs: Long = System.currentTimeMillis(),
    var status: PeerStatus = PeerStatus.IN_ROOM
) {
    val lastSeenAgoMs: Long get() = System.currentTimeMillis() - lastSeenMs
}
