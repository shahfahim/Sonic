package com.sonic.share.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.sonic.share.feature_discovery.DefaultDiscoveryEngine
import com.sonic.share.feature_transfer.domain.TransferManager
import com.sonic.share.ui.radar.RadarScreen
import com.sonic.share.ui.radar.RadarViewModel
import com.sonic.share.ui.theme.SonicDeepNavy
import com.sonic.share.ui.theme.SonicTheme

/**
 * Main launcher entry point for Sonic AirDrop Android.
 * Orchestrates runtime permissions, background transfer engine lifecycle,
 * and Jetpack Compose Radar UI hierarchy.
 */
class MainActivity : ComponentActivity() {

    private var discoveryEngine: DefaultDiscoveryEngine? = null
    private var transferManager: TransferManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothAdapter = try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            bm?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        } catch (_: Throwable) {
            null
        }

        val deviceName = try {
            val btName = bluetoothAdapter?.name
            if (!btName.isNullOrBlank()) btName else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        } catch (_: Throwable) {
            "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        }

        // Initialize core engines
        val engine = DefaultDiscoveryEngine.create(applicationContext)
        val transfer = TransferManager(
            context = applicationContext,
            localDeviceId = engine.deviceId,
            localDeviceName = deviceName,
            localDeviceModel = Build.MODEL,
            localPort = 8989
        )
        transfer.startServer(8989)

        discoveryEngine = engine
        transferManager = transfer

        val permissionsToRequest = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        setContent {
            SonicTheme {
                val viewModel: RadarViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return RadarViewModel(
                                discoveryEngine = engine,
                                transferManager = transfer,
                                autoStartScanning = false,
                                deviceName = deviceName,
                                localPort = 8989
                            ) as T
                        }
                    }
                )

                val enableBluetoothLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    if (hasRequiredConnectivityPermissions(this@MainActivity)) {
                        viewModel.startScanning()
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    // Check if essential connectivity permissions are granted (don't block on optional POST_NOTIFICATIONS)
                    if (hasRequiredConnectivityPermissions(this@MainActivity)) {
                        checkBluetoothAndStart(bluetoothAdapter, enableBluetoothLauncher, viewModel)
                    }
                }

                LaunchedEffect(Unit) {
                    val ungranted = permissionsToRequest.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (ungranted.isNotEmpty()) {
                        permissionLauncher.launch(ungranted.toTypedArray())
                    } else if (hasRequiredConnectivityPermissions(this@MainActivity)) {
                        checkBluetoothAndStart(bluetoothAdapter, enableBluetoothLauncher, viewModel)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SonicDeepNavy
                ) {
                    RadarScreen(viewModel = viewModel)
                }
            }
        }
    }

    private fun checkBluetoothAndStart(
        adapter: android.bluetooth.BluetoothAdapter?,
        enableLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
        viewModel: RadarViewModel
    ) {
        val isBtEnabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            true
        }
        if (adapter != null && !isBtEnabled) {
            try {
                enableLauncher.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Throwable) {
                viewModel.startScanning()
            }
        } else {
            viewModel.startScanning()
        }
    }

    private fun hasRequiredConnectivityPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31-32)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 11 and below (API <= 30)
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryEngine?.stopDiscovery()
        discoveryEngine?.stopAdvertising()
        transferManager?.stopServer()
    }
}
