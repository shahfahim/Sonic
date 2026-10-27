package com.sonic.share.ui.radar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.ui.theme.SonicBlue
import com.sonic.share.ui.theme.SonicCyan
import com.sonic.share.ui.theme.SonicGreen

/**
 * Resolves the appropriate system device icon based on device model string.
 */
fun getDeviceIcon(model: String): ImageVector {
    val lower = model.lowercase()
    return when {
        lower.contains("phone") || lower.contains("pixel") || lower.contains("galaxy") || lower.contains("iphone") || lower.contains("android") -> Icons.Default.PhoneAndroid
        lower.contains("laptop") || lower.contains("macbook") || lower.contains("pc") || lower.contains("notebook") || lower.contains("computer") -> Icons.Default.Laptop
        lower.contains("tablet") || lower.contains("ipad") || lower.contains("tab") -> Icons.Default.Tablet
        else -> Icons.Default.Devices
    }
}

/**
 * Interactive peer device node rendered on the AirDrop radar canvas.
 * Features touch-scale animations, device type icons, and discovery protocol badges.
 */
@Composable
fun PeerAvatarNode(
    peer: PeerDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "PeerNodeScale"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Glowing circular avatar container
        Box(
            modifier = Modifier
                .size(68.dp)
                .shadow(elevation = 8.dp, shape = CircleShape, ambientColor = SonicCyan, spotColor = SonicBlue)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1E293B),
                            Color(0xFF0F172A)
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(SonicCyan, SonicBlue)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getDeviceIcon(peer.model),
                contentDescription = peer.name,
                tint = SonicCyan,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Peer Device Name
        Text(
            text = peer.name.ifBlank { "Unknown Device" },
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 84.dp)
        )

        Spacer(modifier = Modifier.height(2.dp))

        // 3. Connection Type Badge (Wi-Fi Direct / mDNS / BLE)
        val (badgeLabel, badgeBgColor, badgeTextColor) = when (peer.discoveryType) {
            DiscoveryType.WIFI_DIRECT -> Triple("Wi-Fi Direct", SonicGreen.copy(alpha = 0.2f), SonicGreen)
            DiscoveryType.MDNS -> Triple("mDNS", SonicCyan.copy(alpha = 0.2f), SonicCyan)
            DiscoveryType.BLE -> Triple("BLE", Color(0xFF7C4DFF).copy(alpha = 0.25f), Color(0xFFB388FF))
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(badgeBgColor)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = badgeLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = badgeTextColor
            )
        }
    }
}
