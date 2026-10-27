package com.sonic.share.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Sonic Brand Color Palette
val SonicCyan = Color(0xFF00E5FF)
val SonicCyanBright = Color(0xFF18FFFF)
val SonicBlue = Color(0xFF0091EA)
val SonicBlueDark = Color(0xFF01579B)
val SonicDeepNavy = Color(0xFF0A0E17)
val SonicSurfaceDark = Color(0xFF121B2A)
val SonicSurfaceVariantDark = Color(0xFF1E293B)
val SonicGreen = Color(0xFF00E676)
val SonicRed = Color(0xFFFF5252)

private val DarkColorScheme = darkColorScheme(
    primary = SonicCyan,
    onPrimary = Color.Black,
    primaryContainer = SonicBlueDark,
    onPrimaryContainer = SonicCyanBright,
    secondary = SonicBlue,
    onSecondary = Color.White,
    background = SonicDeepNavy,
    onBackground = Color(0xFFF1F5F9),
    surface = SonicSurfaceDark,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = SonicSurfaceVariantDark,
    onSurfaceVariant = Color(0xFF94A3B8),
    error = SonicRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = SonicBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F7FA),
    onPrimaryContainer = SonicBlueDark,
    secondary = SonicCyan,
    onSecondary = Color.Black,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    error = SonicRed,
    onError = Color.White
)

@Composable
fun SonicTheme(
    darkTheme: Boolean = true, // Default to sleek futuristic dark radar theme
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
