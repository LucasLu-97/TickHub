package com.lucaslu.tickhub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Typography

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

// tabular numbers: digits share one fixed width so times/counts align neatly,
// without forcing the whole text (e.g. Chinese) into a monospace face.
private fun TextStyle.withTabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.withTabularNumbers(),
        displayMedium = t.displayMedium.withTabularNumbers(),
        displaySmall = t.displaySmall.withTabularNumbers(),
        headlineLarge = t.headlineLarge.withTabularNumbers(),
        headlineMedium = t.headlineMedium.withTabularNumbers(),
        headlineSmall = t.headlineSmall.withTabularNumbers(),
        titleLarge = t.titleLarge.withTabularNumbers(),
        titleMedium = t.titleMedium.withTabularNumbers(),
        titleSmall = t.titleSmall.withTabularNumbers(),
        bodyLarge = t.bodyLarge.withTabularNumbers(),
        bodyMedium = t.bodyMedium.withTabularNumbers(),
        bodySmall = t.bodySmall.withTabularNumbers(),
        labelLarge = t.labelLarge.withTabularNumbers(),
        labelMedium = t.labelMedium.withTabularNumbers(),
        labelSmall = t.labelSmall.withTabularNumbers(),
    )
}

@Composable
fun TickHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
