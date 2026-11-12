package com.github.vermilion10.pancar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LiveRed = Color(0xFFE8452C)

private val Scheme = darkColorScheme(
    primary = LiveRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A1A10),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFFE7BDB4),
    tertiary = Color(0xFFF2B84B),
    background = Color(0xFF121016),
    onBackground = Color(0xFFE8E1E9),
    surface = Color(0xFF121016),
    onSurface = Color(0xFFE8E1E9),
    surfaceContainer = Color(0xFF1E1B22),
    surfaceContainerHigh = Color(0xFF29252D),
    surfaceVariant = Color(0xFF2E2A33),
    onSurfaceVariant = Color(0xFFCBC3CD),
    outline = Color(0xFF8A8494),
)

// Always dark: the app is used right before jumping into a game.
@Composable
fun PancarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
