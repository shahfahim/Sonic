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

data class ScanFilterSpec(
    val serviceDataUuid: java.util.UUID,
    val serviceData: ByteArray? = null
)

class BleScannerManager(
    private val bluetoothAdapter: BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Throwable) {
        null
    }
) : BleScanner {

    companion object {
        private const val TAG = "BleScannerManager"

        fun getScanFilterSpec(uuid: java.util.UUID = BleAdvertiserManager.SONIC_SERVICE_UUID): ScanFilterSpec {
            return ScanFilterSpec(serviceDataUuid = uuid, serviceData = null)
        }

        fun createScanFilter(pUuid: ParcelUuid = ParcelUuid(BleAdvertiserManager.SONIC_SERVICE_UUID)): ScanFilter? {
            return try {
                ScanFilter.Builder()
                    .setServiceData(pUuid, null)
                    .build()
            } catch (_: Throwable) {
                null
            }
        }
    }

    override var isScanning: Boolean = false
        private set

    private var activeCallback: ScanCallback? = null

    override fun startScan(
        onPeerDiscovered: (PeerDevice) -> Unit,
        onError: ((Int) -> Unit)?
    ) {
        val adapter = bluetoothAdapter

        val isEnabled = try {
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            safeLogE(TAG, "SecurityException checking bluetoothAdapter.isEnabled", e)
            onError?.invoke(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
            return
        } catch (e: Throwable) {
            safeLogE(TAG, "Error checking bluetoothAdapter.isEnabled", e)
            false
        }

        if (adapter == null || !isEnabled) {
            safeLogW(TAG, "Bluetooth adapter unavailable or disabled")
            onError?.invoke(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
            return
        }

        val scanner: BluetoothLeScanner? = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            safeLogE(TAG, "SecurityException acquiring BluetoothLeScanner", e)
            onError?.invoke(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
            return
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
        val filter = createScanFilter(pUuid)
        val filters = if (filter != null) listOf(filter) else emptyList()

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
            // Using unfiltered hardware scan (null) avoids OEM chipset firmware bugs (e.g. Samsung/MediaTek dropping packets).
            // handleScanResult guarantees strict software filtering matching SONIC_SERVICE_UUID and BleBeaconPayload.
            scanner.startScan(null, settings, callback)
            isScanning = true
            safeLogD(TAG, "BLE scanning started successfully (unfiltered hardware scan, software-filtered)")
        } catch (e: SecurityException) {
            safeLogE(TAG, "Missing BLUETOOTH_SCAN permission", e)
            isScanning = false
            onError?.invoke(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        } catch (e: Throwable) {
            // Fallback for custom ROMs that might reject null filter
            try {
                scanner.startScan(emptyList(), settings, callback)
                isScanning = true
                safeLogD(TAG, "BLE scanning started with emptyList() fallback")
            } catch (t: Throwable) {
                safeLogE(TAG, "Failed to start BLE scan", t)
                isScanning = false
                onError?.invoke(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            }
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
            } catch (e: SecurityException) {
                safeLogW(TAG, "SecurityException stopping BLE scan", e)
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

        // Prefer device name from scan record / response if available, then payload, then BT device
        val deviceName = record.deviceName
            ?: payload.deviceName.ifBlank {
                try {
                    result.device?.name
                } catch (_: SecurityException) {
                    null
                } ?: "Nearby Sonic Device"
            }

        val deviceAddress = try {
            result.device?.address ?: "unknown-ble"
        } catch (_: SecurityException) {
            "unknown-ble"
        }

        val peer = PeerDevice(
            id = payload.deviceId.ifBlank { deviceAddress },
            name = deviceName,
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
