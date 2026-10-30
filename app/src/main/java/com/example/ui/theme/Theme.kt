package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TechDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF002026),
    primaryContainer = Color(0xFF003740),
    onPrimaryContainer = Color(0xFF82F4FF),
    secondary = MatrixGreen,
    onSecondary = Color(0xFF00220E),
    secondaryContainer = Color(0xFF003A19),
    onSecondaryContainer = Color(0xFF70FF9A),
    tertiary = ElectricAmber,
    onTertiary = Color(0xFF261A00),
    tertiaryContainer = Color(0xFF422E00),
    onTertiaryContainer = Color(0xFFFFD980),
    error = LaserCrimson,
    onError = Color.White,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkCardBorder
)

@Composable
fun LLMLabTheme(
    darkTheme: Boolean = true, // Technische Testbench standardmäßig im Dark-Mode
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TechDarkColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    LLMLabTheme(darkTheme = true, content = content)
}
