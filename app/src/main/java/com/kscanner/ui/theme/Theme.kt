package com.kscanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = ScanTeal,
    onPrimary = Color.White,
    primaryContainer = ScanTealContainer,
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = ScanBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF0B2A6B),
    background = LightBackground,
    onBackground = Color(0xFF1A1C1E),
    surface = LightSurface,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE6E9ED),
    onSurfaceVariant = Color(0xFF44474B),
    outline = Color(0xFF9AA0A6),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = ScanTealLight,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF115E56),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF9DBBFF),
    onSecondary = Color(0xFF00205B),
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFDBEAFE),
    background = DarkBackground,
    onBackground = Color(0xFFE3E5E8),
    surface = DarkSurface,
    onSurface = Color(0xFFE3E5E8),
    surfaceVariant = Color(0xFF2A2E31),
    onSurfaceVariant = Color(0xFFC3C7CB),
    outline = Color(0xFF6F7478),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun KScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = KScannerTypography,
        content = content,
    )
}
