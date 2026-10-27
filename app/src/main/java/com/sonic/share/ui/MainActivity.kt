package com.sonic.share.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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

        // Initialize core engines
        val engine = DefaultDiscoveryEngine.create(applicationContext)
        val transfer = TransferManager(context = applicationContext)
        transfer.startServer()

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
                var permissionsGranted by remember {
                    mutableStateOf(
                        permissionsToRequest.all {
                            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    permissionsGranted = result.values.all { it }
                    if (permissionsGranted) {
                        engine.startDiscovery()
                    }
                }

                LaunchedEffect(Unit) {
                    val ungranted = permissionsToRequest.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (ungranted.isNotEmpty()) {
                        permissionLauncher.launch(ungranted.toTypedArray())
                    } else {
                        permissionsGranted = true
                        engine.startDiscovery()
                    }
                }

                val viewModel = remember {
                    RadarViewModel(
                        discoveryEngine = engine,
                        transferManager = transfer
                    )
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

    override fun onDestroy() {
        super.onDestroy()
        discoveryEngine?.stopDiscovery()
        transferManager?.stopServer()
    }
}
