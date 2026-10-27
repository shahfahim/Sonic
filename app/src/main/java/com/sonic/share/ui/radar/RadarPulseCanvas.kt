package com.sonic.share.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sonic.share.ui.theme.SonicBlue
import com.sonic.share.ui.theme.SonicCyan
import kotlin.math.min

/**
 * AirDrop-style radar pulse canvas.
 * Draws glowing cyan/electric blue concentric ripples expanding outwards.
 */
@Composable
fun RadarPulseCanvas(
    modifier: Modifier = Modifier,
    isScanning: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulseTransition")

    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseProgress"
    )

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val center = Offset(canvasWidth / 2f, canvasHeight / 2f)
        val maxRadius = min(canvasWidth, canvasHeight) / 2f
        val minRadius = 50.dp.toPx()

        // 1. Center soft radial ambient glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    SonicCyan.copy(alpha = if (isScanning) 0.25f else 0.08f),
                    SonicBlue.copy(alpha = if (isScanning) 0.12f else 0.04f),
                    Color.Transparent
                ),
                center = center,
                radius = maxRadius * 0.75f
            ),
            center = center,
            radius = maxRadius * 0.75f
        )

        // 2. Static subtle baseline radar guide circles
        val ringCount = 4
        for (i in 1..ringCount) {
            val guideRadius = minRadius + (maxRadius - minRadius) * (i / ringCount.toFloat())
            drawCircle(
                color = SonicBlue.copy(alpha = 0.15f),
                center = center,
                radius = guideRadius,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // 3. Dynamic expanding concentric pulse waves
        if (isScanning) {
            val waveCount = 4
            for (i in 0 until waveCount) {
                val waveFraction = (pulseProgress + i.toFloat() / waveCount) % 1f
                val waveRadius = minRadius + waveFraction * (maxRadius - minRadius)
                // Alpha fades out towards the outer edge
                val waveAlpha = ((1f - waveFraction) * 0.7f).coerceIn(0f, 0.7f)
                val strokeWidth = (2.5f - waveFraction * 1.5f).dp.toPx()

                drawCircle(
                    color = SonicCyan.copy(alpha = waveAlpha),
                    center = center,
                    radius = waveRadius,
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }
}
