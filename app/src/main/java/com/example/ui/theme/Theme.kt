package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StageDarkColorScheme = darkColorScheme(
    primary = PedalCrimson,
    onPrimary = Color.White,
    primaryContainer = PedalCrimsonDeep,
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = LedAmber,
    onSecondary = Color(0xFF1A1200),
    secondaryContainer = Color(0xFF3A2900),
    onSecondaryContainer = Color(0xFFFFE082),
    tertiary = LedBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF0E2954),
    onTertiaryContainer = Color(0xFFD6E4FF),
    background = StageObsidian,
    onBackground = TextPrimary,
    surface = StageRackSurface,
    onSurface = TextPrimary,
    surfaceVariant = StagePanelElevated,
    onSurfaceVariant = TextSecondary,
    outline = StagePanelBorder,
    outlineVariant = Color(0xFF222631),
    error = LedPeakRed,
    onError = Color.White
)

val StageShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

@Composable
fun VocalStageTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StageDarkColorScheme,
        typography = AppTypography,
        shapes = StageShapes,
        content = content
    )
}
