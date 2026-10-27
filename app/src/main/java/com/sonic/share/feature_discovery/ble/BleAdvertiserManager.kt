package com.sonic.share.feature_discovery.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

interface BleAdvertiser {
    val isAdvertising: Boolean
    fun startAdvertising(
        deviceId: String,
        deviceName: String,
        port: Int,
        model: String = "Android",
        onSuccess: (() -> Unit)? = null,
        onError: ((Int) -> Unit)? = null
    )
    fun stopAdvertising()
}

data class AdvertiseSettingsSpec(
    val advertiseMode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
    val txPowerLevel: Int = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
    val isConnectable: Boolean = false,
    val timeoutMillis: Int = 0
)

class BleAdvertiserManager(
    private val bluetoothAdapter: BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Throwable) {
        null
    }
) : BleAdvertiser {

    companion object {
        private const val TAG = "BleAdvertiserManager"
        // 16-bit short UUID format: 0000534F-0000-1000-8000-00805F9B34FB ("SO")
        val SONIC_SERVICE_UUID: UUID = UUID.fromString("0000534f-0000-1000-8000-00805f9b34fb")

        fun getAdvertiseSettingsSpec(): AdvertiseSettingsSpec {
            return AdvertiseSettingsSpec(
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                isConnectable = false,
                timeoutMillis = 0
            )
        }

        fun createAdvertiseSettings(): AdvertiseSettings? {
            return try {
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build()
            } catch (_: Throwable) {
                null
            }
        }

        fun createAdvertiseData(pUuid: ParcelUuid, payload: ByteArray): AdvertiseData? {
            return try {
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceData(pUuid, payload)
                    .build()
            } catch (_: Throwable) {
                null
            }
        }
    }

    override var isAdvertising: Boolean = false
        private set

    private var activeCallback: AdvertiseCallback? = null

    override fun startAdvertising(
        deviceId: String,
        deviceName: String,
        port: Int,
        model: String,
        onSuccess: (() -> Unit)?,
        onError: ((Int) -> Unit)?
    ) {
        val adapter = bluetoothAdapter

        val isEnabled = try {
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            safeLogE(TAG, "SecurityException checking bluetoothAdapter.isEnabled", e)
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            return
        } catch (e: Throwable) {
            safeLogE(TAG, "Error checking bluetoothAdapter.isEnabled", e)
            false
        }

        if (adapter == null || !isEnabled) {
            safeLogW(TAG, "Bluetooth adapter unavailable or disabled")
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            return
        }

        val advertiser: BluetoothLeAdvertiser? = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: SecurityException) {
            safeLogE(TAG, "SecurityException acquiring BluetoothLeAdvertiser", e)
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            return
        } catch (e: Throwable) {
            safeLogE(TAG, "Could not acquire BluetoothLeAdvertiser", e)
            null
        }

        if (advertiser == null) {
            safeLogW(TAG, "BLE advertising not supported on this device")
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            return
        }

        if (isAdvertising) {
            stopAdvertising()
        }

        val settings = createAdvertiseSettings() ?: AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val pUuid = ParcelUuid(SONIC_SERVICE_UUID)
        val shortId = if (deviceId.length > BleBeaconPayload.MAX_ID_BYTES) {
            deviceId.substring(0, BleBeaconPayload.MAX_ID_BYTES)
        } else {
            deviceId
        }
        val payload = BleBeaconPayload(
            deviceId = shortId,
            deviceName = deviceName,
            model = model,
            port = port
        ).toByteArray()

        // Primary advertising packet: ONLY service data (contains UUID + compact payload)
        // Strictly avoid addServiceUuid to stay well below the 31-byte legacy limit
        val advertiseData = createAdvertiseData(pUuid, payload) ?: AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(pUuid, payload)
            .build()

        // Scan response packet: secondary channel for device name broadcast
        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                safeLogD(TAG, "BLE advertisement started successfully")
                isAdvertising = true
                onSuccess?.invoke()
            }

            override fun onStartFailure(errorCode: Int) {
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    safeLogW(TAG, "BLE advertisement data too large, retrying without scan response")
                    try {
                        advertiser.startAdvertising(settings, advertiseData, this)
                        return
                    } catch (_: Throwable) { }
                }
                safeLogE(TAG, "BLE advertisement failed with code: $errorCode")
                isAdvertising = false
                onError?.invoke(errorCode)
            }
        }

        activeCallback = callback
        try {
            advertiser.startAdvertising(settings, advertiseData, scanResponseData, callback)
        } catch (e: SecurityException) {
            safeLogE(TAG, "Missing BLUETOOTH_ADVERTISE permission", e)
            isAdvertising = false
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        } catch (e: Throwable) {
            // Fallback retry without scan response on legacy Bluetooth stacks
            try {
                advertiser.startAdvertising(settings, advertiseData, callback)
            } catch (t: Throwable) {
                safeLogE(TAG, "Failed to start BLE advertising", t)
                isAdvertising = false
                onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            }
        }
    }

    override fun stopAdvertising() {
        val advertiser = try {
            bluetoothAdapter?.bluetoothLeAdvertiser
        } catch (_: Throwable) {
            null
        }
        val callback = activeCallback
        if (advertiser != null && callback != null) {
            try {
                advertiser.stopAdvertising(callback)
            } catch (e: SecurityException) {
                safeLogW(TAG, "SecurityException stopping BLE advertising", e)
            } catch (e: Throwable) {
                safeLogW(TAG, "Error stopping BLE advertising", e)
            }
        }
        activeCallback = null
        isAdvertising = false
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
