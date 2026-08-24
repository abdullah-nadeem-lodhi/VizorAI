package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MonochromeColorScheme = darkColorScheme(
    primary = InverseBackground,
    onPrimary = InverseText,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = InverseText,
    secondaryContainer = SurfaceHigh,
    onSecondaryContainer = TextPrimary,
    tertiary = TextMuted,
    onTertiary = TextPrimary,
    background = BgCanvas,
    onBackground = TextPrimary,
    surface = SurfaceBase,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = BorderStrong
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MonochromeColorScheme,
        typography = Typography,
        content = content
    )
}
