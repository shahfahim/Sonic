package com.sonic.share.ui.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonic.share.core.service.TransferNotificationHelper
import com.sonic.share.domain.model.TransferSession
import com.sonic.share.domain.model.TransferState
import com.sonic.share.ui.sheet.formatBytes
import com.sonic.share.ui.theme.SonicBlue
import com.sonic.share.ui.theme.SonicCyan
import com.sonic.share.ui.theme.SonicGreen
import com.sonic.share.ui.theme.SonicRed
import com.sonic.share.ui.theme.SonicSurfaceDark

/**
 * Floating interactive HUD card displaying real-time transfer metrics,
 * cryptographic handshake progress, animated percentages, speed, and ETA.
 */
@Composable
fun TransferProgressHUD(
    session: TransferSession,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    TransferProgressHUD(
        transferState = session.state,
        peerName = session.peer.name,
        isIncoming = session.isIncoming,
        onCancel = onCancel,
        modifier = modifier
    )
}

/**
 * Core Composable overload consuming [TransferState].
 */
@Composable
fun TransferProgressHUD(
    transferState: TransferState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    TransferProgressHUD(
        transferState = transferState,
        peerName = "Nearby Device",
        isIncoming = false,
        onCancel = onCancel,
        modifier = modifier
    )
}

@Composable
fun TransferProgressHUD(
    transferState: TransferState,
    peerName: String,
    isIncoming: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = transferState !is TransferState.Idle,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp), ambientColor = SonicCyan, spotColor = SonicBlue)
                .border(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(SonicCyan.copy(alpha = 0.6f), SonicBlue.copy(alpha = 0.6f))
                    ),
                    shape = RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = SonicSurfaceDark.copy(alpha = 0.95f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                when (transferState) {
                    is TransferState.Connecting -> {
                        TransferStatusRow(
                            title = if (isIncoming) "Incoming Connection" else "Connecting...",
                            subtitle = "Establishing peer link with $peerName",
                            isLoading = true,
                            onCancel = onCancel
                        )
                    }

                    is TransferState.Handshaking -> {
                        TransferStatusRow(
                            title = "Securing Channel",
                            subtitle = "Deriving ECDH key pair & AES-256 session key",
                            isLoading = true,
                            onCancel = onCancel
                        )
                    }

                    is TransferState.Transferring -> {
                        val percent = (transferState.progress * 100).toInt().coerceIn(0, 100)
                        val speed = TransferNotificationHelper.formatSpeed(transferState.speedBytesPerSec)
                        val remainingBytes = (transferState.totalBytes - transferState.bytesTransferred).coerceAtLeast(0L)
                        val eta = TransferNotificationHelper.formatEta(remainingBytes, transferState.speedBytesPerSec)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(52.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { transferState.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.size(52.dp),
                                    color = SonicCyan,
                                    trackColor = Color(0xFF1E293B),
                                    strokeWidth = 4.dp
                                )
                                Text(
                                    text = "$percent%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isIncoming) "Receiving from $peerName" else "Sending to $peerName",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$speed • ETA: $eta",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SonicCyan,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                                Text(
                                    text = "${formatBytes(transferState.bytesTransferred)} / ${formatBytes(transferState.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                )
                            }

                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF334155))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel transfer",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        LinearProgressIndicator(
                            progress = { transferState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = SonicCyan,
                            trackColor = Color(0xFF1E293B)
                        )
                    }

                    is TransferState.Completed -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SonicGreen,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Transfer Complete",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                                Text(
                                    text = "Files saved and verified successfully",
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                                )
                            }
                            IconButton(onClick = onCancel) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    is TransferState.Failed -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = SonicRed,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Transfer Failed",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SonicRed
                                    )
                                )
                                Text(
                                    text = transferState.reason,
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8)),
                                    maxLines = 2
                                )
                            }
                            IconButton(onClick = onCancel) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    is TransferState.Idle -> {
                        // Handled by AnimatedVisibility
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferStatusRow(
    title: String,
    subtitle: String,
    isLoading: Boolean,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = SonicCyan,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF94A3B8)
                )
            )
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF334155))
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
