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

class BleAdvertiserManager(
    private val bluetoothAdapter: BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Throwable) {
        null
    }
) : BleAdvertiser {

    companion object {
        private const val TAG = "BleAdvertiserManager"
        val SONIC_SERVICE_UUID: UUID = UUID.fromString("0000534f-0000-1000-8000-00805f9b34fb")
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
        if (adapter == null || !adapter.isEnabled) {
            safeLogW(TAG, "Bluetooth adapter unavailable or disabled")
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            return
        }

        val advertiser: BluetoothLeAdvertiser? = try {
            adapter.bluetoothLeAdvertiser
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

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val pUuid = ParcelUuid(SONIC_SERVICE_UUID)
        val payload = BleBeaconPayload(deviceId, deviceName, model, port).toByteArray()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(pUuid)
            .addServiceData(pUuid, payload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                safeLogD(TAG, "BLE advertisement started successfully")
                isAdvertising = true
                onSuccess?.invoke()
            }

            override fun onStartFailure(errorCode: Int) {
                safeLogE(TAG, "BLE advertisement failed with code: $errorCode")
                isAdvertising = false
                onError?.invoke(errorCode)
            }
        }

        activeCallback = callback
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            safeLogE(TAG, "Missing BLUETOOTH_ADVERTISE permission", e)
            isAdvertising = false
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        } catch (e: Throwable) {
            safeLogE(TAG, "Failed to start BLE advertising", e)
            isAdvertising = false
            onError?.invoke(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
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
