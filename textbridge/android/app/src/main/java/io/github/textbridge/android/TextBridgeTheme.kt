package io.github.textbridge.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    secondary = Color(0xFF4D6358),
    background = Color(0xFFF8FAF8),
    surface = Color(0xFFF8FAF8),
    surfaceContainer = Color(0xFFECEFEB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF72DDB1),
    onPrimary = Color(0xFF003827),
    secondary = Color(0xFFB5CCBF),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
    surfaceContainer = Color(0xFF1C211E),
)

@Composable
fun TextBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
