package com.sonic.share.ui.radar

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.domain.model.TransferPayload
import com.sonic.share.ui.sheet.IncomingTransferBottomSheet
import com.sonic.share.ui.theme.SonicBlue
import com.sonic.share.ui.theme.SonicCyan
import com.sonic.share.ui.theme.SonicDeepNavy
import com.sonic.share.ui.theme.SonicGreen
import com.sonic.share.ui.theme.SonicSurfaceDark
import com.sonic.share.ui.theme.SonicSurfaceVariantDark
import com.sonic.share.ui.transfer.TransferProgressHUD
import java.util.UUID
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Main AirDrop-style radar screen for Sonic.
 * Displays polar radar canvas, concentric pulse waves, nearby peers, and active transfer HUD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    viewModel: RadarViewModel,
    onSendFiles: (PeerDevice) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var targetPeerForTransfer by remember { mutableStateOf<PeerDevice?>(null) }

    // Multi-file picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val peer = targetPeerForTransfer
        if (peer != null && uris.isNotEmpty()) {
            val payloads = uris.mapNotNull { uri -> createPayloadFromUri(context, uri) }
            if (payloads.isNotEmpty()) {
                viewModel.startTransfer(peer, payloads)
                onSendFiles(peer)
            }
        }
        targetPeerForTransfer = null
    }

    Scaffold(
        topBar = {
            RadarTopBar(
                isScanning = uiState.isScanning,
                peerCount = uiState.discoveredPeers.size,
                onToggleScanning = { viewModel.toggleScanning() }
            )
        },
        containerColor = SonicDeepNavy
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Radar Pulse Canvas Background
            RadarPulseCanvas(
                modifier = Modifier.fillMaxSize(),
                isScanning = uiState.isScanning
            )

            // 2. Polar Device Layout Area
            PolarRadarLayout(
                peers = uiState.discoveredPeers,
                onPeerClick = { peer ->
                    viewModel.selectPeer(peer)
                    targetPeerForTransfer = peer
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxSize()
            )

            // 3. Floating Transfer Progress HUD
            val activeSession = uiState.activeTransfer
            if (activeSession != null) {
                TransferProgressHUD(
                    session = activeSession,
                    onCancel = {
                        viewModel.cancelActiveTransfer()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // 4. Modal Bottom Sheet for Incoming Transfers
            val incoming = uiState.incomingRequest
            if (incoming != null) {
                IncomingTransferBottomSheet(
                    session = incoming,
                    onAccept = { viewModel.acceptIncomingTransfer() },
                    onDecline = { viewModel.declineIncomingTransfer() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadarTopBar(
    isScanning: Boolean,
    peerCount: Int,
    onToggleScanning: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(SonicCyan, SonicBlue)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "Sonic",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "Sonic",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            color = Color.White
                        )
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isScanning) SonicGreen else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isScanning) {
                                if (peerCount > 0) "$peerCount nearby" else "Scanning nearby..."
                            } else "Radar paused",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = onToggleScanning,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SonicSurfaceVariantDark)
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isScanning) "Pause Scanning" else "Resume Scanning",
                    tint = SonicCyan
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = SonicDeepNavy
        )
    )
}

@Composable
private fun PolarRadarLayout(
    peers: List<PeerDevice>,
    onPeerClick: (PeerDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val centerX = widthPx / 2f
        val centerY = heightPx / 2f
        val maxRadarRadius = min(widthPx, heightPx) / 2f - with(density) { 56.dp.toPx() }

        // Center Local Device Node
        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            LocalDeviceNode()
        }

        // Empty state hint
        if (peers.isEmpty()) {
            Text(
                text = "Tap any detected peer to send files",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            )
        }

        // Radial Peer Nodes ($r, \theta$)
        peers.forEachIndexed { index, peer ->
            val angle = (-Math.PI / 2) + (2 * Math.PI * index / peers.size.toDouble())
            val radiusFraction = if (peer.rssi != 0) {
                ((-peer.rssi - 30) / 60f).coerceIn(0.42f, 0.88f)
            } else {
                0.65f
            }
            val radius = maxRadarRadius * radiusFraction

            val nodeWidthPx = with(density) { 84.dp.toPx() }
            val nodeHeightPx = with(density) { 104.dp.toPx() }

            val posX = centerX + radius * cos(angle).toFloat() - (nodeWidthPx / 2f)
            val posY = centerY + radius * sin(angle).toFloat() - (nodeHeightPx / 2f)

            Box(
                modifier = Modifier.offset {
                    IntOffset(posX.roundToInt(), posY.roundToInt())
                }
            ) {
                PeerAvatarNode(
                    peer = peer,
                    onClick = { onPeerClick(peer) }
                )
            }
        }
    }
}

@Composable
private fun LocalDeviceNode() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .shadow(12.dp, CircleShape, ambientColor = SonicCyan, spotColor = SonicBlue)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SonicSurfaceDark,
                            SonicDeepNavy
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(SonicCyan, SonicBlue, SonicCyan)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Smartphone,
                contentDescription = "My Device",
                tint = SonicCyan,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "This Device",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color.White
            )
        )
    }
}

private fun createPayloadFromUri(context: Context, uri: Uri): TransferPayload? {
    return try {
        var name = "unknown_file"
        var size = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }

        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        TransferPayload(
            id = UUID.randomUUID().toString(),
            name = name,
            size = size,
            mimeType = mimeType,
            sha256 = "",
            uriString = uri.toString()
        )
    } catch (_: Exception) {
        null
    }
}
