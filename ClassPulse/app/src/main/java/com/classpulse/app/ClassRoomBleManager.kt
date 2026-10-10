package com.classpulse.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.util.UUID

/**
 * BLE mesh for the classroom.
 *
 * Every phone BOTH advertises and scans on the shared service UUID.
 * Payload (service data, 8 bytes): [roomHash(2) | userHash(2) | steps(2) | flags(2)].
 * Display names are resolved locally: short id -> "Student-XXXX". The full name typed
 * in the UI is embedded in the advertise device name (truncated) when space allows.
 *
 * Callbacks deliver peer sightings (deviceId, rssi, steps) to [onPeerSeen].
 */
class ClassRoomBleManager(
    private val context: Context,
    var onPeerSeen: ((deviceId: String, displayName: String, rssi: Int, steps: Int) -> Unit)? = null,
    var onError: ((String) -> Unit)? = null
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000c1a5-0000-1000-8000-00805f9b34fb")
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertising = false
    private var scanning = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
        override fun onStartFailure(errorCode: Int) {
            onError?.invoke("Advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }
        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handle(it) }
        }
        override fun onScanFailed(errorCode: Int) {
            onError?.invoke("Scan failed: $errorCode")
        }
    }

    private fun handle(result: ScanResult) {
        val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
        if (bytes.size < 8) return
        val buf = ByteBuffer.wrap(bytes)
        val roomHash = buf.short.toInt() and 0xFFFF
        val userHash = buf.short.toInt() and 0xFFFF
        val steps = buf.short.toInt() and 0xFFFF
        @Suppress("UNUSED_VARIABLE") val flags = buf.short
        if (roomHash != currentRoomHash) return
        val mac = result.device?.address ?: return
        val recName = result.scanRecord?.deviceName ?: result.device?.name
        val label = if (!recName.isNullOrBlank()) recName else "Student-%04X".format(userHash)
        onPeerSeen?.invoke(mac, label, result.rssi, steps)
    }

    private var currentRoomHash: Int = 0
    private var currentUserHash: Int = 0
    private var currentSteps: Int = 0

    fun roomHashOf(roomCode: String): Int =
        roomCode.trim().uppercase().hashCode() and 0xFFFF

    @SuppressLint("MissingPermission")
    fun start(roomCode: String, displayName: String, steps: Int = 0) {
        currentRoomHash = roomHashOf(roomCode)
        currentUserHash = displayName.hashCode() and 0xFFFF
        currentSteps = steps
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            onError?.invoke("Bluetooth is off or unavailable")
            return
        }
        startAdvertiser()
        startScanner()
    }

    @SuppressLint("MissingPermission")
    fun updateSteps(steps: Int) {
        currentSteps = steps
        // Re-advertise so peers see the fresh step count (cheap: restart advertising).
        if (advertising) {
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
            advertising = false
            startAdvertiser()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertiser() {
        try {
            advertiser = adapter?.bluetoothLeAdvertiser
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            val payload = ByteBuffer.allocate(8)
                .putShort(currentRoomHash.toShort())
                .putShort(currentUserHash.toShort())
                .putShort(currentSteps.toShort())
                .putShort(0)
                .array()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .addServiceData(ParcelUuid(SERVICE_UUID), payload)
                .setIncludeDeviceName(false)
                .build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            advertising = true
        } catch (e: Exception) {
            onError?.invoke("Advertise error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanner() {
        try {
            scanner = adapter?.bluetoothLeScanner
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (e: Exception) {
            onError?.invoke("Scan error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { if (advertising) advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { if (scanning) scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        advertising = false
        scanning = false
    }
}
