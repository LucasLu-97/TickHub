package com.lucaslu.tickhub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * MIUI-like palette: light-grey page background with white content cards.
 * (Material3's default has a white background + grey cards — the reverse.)
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B7CFF),
    onPrimary = Color.White,
    secondary = Color(0xFF6650A4),
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEEF0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F3F5),
    surfaceContainerHighest = Color(0xFFEDEEF0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    secondary = Color(0xFFCFB9FF),
    background = Color(0xFF17181A),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF232427),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF2C2D30),
    surfaceContainerLowest = Color(0xFF1B1C1E),
    surfaceContainerLow = Color(0xFF1F2022),
    surfaceContainer = Color(0xFF232427),
    surfaceContainerHigh = Color(0xFF2A2B2E),
    surfaceContainerHighest = Color(0xFF323337),
)

@Composable
fun TickHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
