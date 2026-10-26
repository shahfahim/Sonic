package com.sonic.share.feature_discovery.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice

interface BleScanner {
    val isScanning: Boolean
    fun startScan(
        onPeerDiscovered: (PeerDevice) -> Unit,
        onError: ((Int) -> Unit)? = null
    )
    fun stopScan()
}

class BleScannerManager(
    private val bluetoothAdapter: BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Throwable) {
        null
    }
) : BleScanner {

    companion object {
        private const val TAG = "BleScannerManager"
    }

    override var isScanning: Boolean = false
        private set

    private var activeCallback: ScanCallback? = null

    override fun startScan(
        onPeerDiscovered: (PeerDevice) -> Unit,
        onError: ((Int) -> Unit)?
    ) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            safeLogW(TAG, "Bluetooth adapter unavailable or disabled")
            onError?.invoke(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            return
        }

        val scanner: BluetoothLeScanner? = try {
            adapter.bluetoothLeScanner
        } catch (e: Throwable) {
            safeLogE(TAG, "Could not acquire BluetoothLeScanner", e)
            null
        }

        if (scanner == null) {
            safeLogW(TAG, "BLE scanner not available")
            onError?.invoke(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            return
        }

        if (isScanning) {
            stopScan()
        }

        val pUuid = ParcelUuid(BleAdvertiserManager.SONIC_SERVICE_UUID)
        val filter = ScanFilter.Builder()
            .setServiceUuid(pUuid)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { handleScanResult(it, onPeerDiscovered) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it, onPeerDiscovered) }
            }

            override fun onScanFailed(errorCode: Int) {
                safeLogE(TAG, "BLE scan failed with error code: $errorCode")
                isScanning = false
                onError?.invoke(errorCode)
            }
        }

        activeCallback = callback
        try {
            scanner.startScan(listOf(filter), settings, callback)
            isScanning = true
            safeLogD(TAG, "BLE scanning started successfully")
        } catch (e: SecurityException) {
            safeLogE(TAG, "Missing BLUETOOTH_SCAN permission", e)
            isScanning = false
            onError?.invoke(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        } catch (e: Throwable) {
            safeLogE(TAG, "Failed to start BLE scan", e)
            isScanning = false
            onError?.invoke(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        }
    }

    override fun stopScan() {
        val scanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (_: Throwable) {
            null
        }
        val callback = activeCallback
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (e: Throwable) {
                safeLogW(TAG, "Error stopping BLE scan", e)
            }
        }
        activeCallback = null
        isScanning = false
    }

    private fun handleScanResult(result: ScanResult, onPeerDiscovered: (PeerDevice) -> Unit) {
        val record = result.scanRecord ?: return
        val pUuid = ParcelUuid(BleAdvertiserManager.SONIC_SERVICE_UUID)
        val serviceData = record.getServiceData(pUuid) ?: return
        val payload = BleBeaconPayload.parse(serviceData) ?: return

        val peer = PeerDevice(
            id = payload.deviceId.ifBlank { result.device?.address ?: "unknown-ble" },
            name = payload.deviceName.ifBlank { result.device?.name ?: "Nearby Sonic Device" },
            model = payload.model.ifBlank { "Android" },
            ipAddress = "",
            port = payload.port,
            rssi = result.rssi,
            discoveryType = DiscoveryType.BLE
        )
        onPeerDiscovered(peer)
    }

    private fun safeLogD(tag: String, msg: String) {
        try { Log.d(tag, msg) } catch (_: Throwable) { }
    }

    private fun safeLogW(tag: String, msg: String, tr: Throwable? = null) {
        try { Log.w(tag, msg, tr) } catch (_: Throwable) { }
    }

    private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
        try { Log.e(tag, msg, tr) } catch (_: Throwable) { }
    }
}
