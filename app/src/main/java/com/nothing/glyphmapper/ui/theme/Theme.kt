package com.nothing.glyphmapper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NothingBlack = Color(0xFF000000)
val NothingCard = Color(0xFF121212)
val NothingCardElevated = Color(0xFF1C1C1C)
val NothingHairline = Color(0xFF2A2A2A)
val NothingWhite = Color(0xFFF5F5F5)
val NothingMuted = Color(0xFF8E8E93)
val NothingRed = Color(0xFFD71921)
val NothingGreen = Color(0xFF34C759)

private val NothingDarkColorScheme = darkColorScheme(
    primary = NothingRed,
    onPrimary = NothingWhite,
    primaryContainer = NothingRed.copy(alpha = 0.18f),
    onPrimaryContainer = NothingWhite,
    secondary = NothingWhite,
    onSecondary = NothingBlack,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingCard,
    onSurface = NothingWhite,
    surfaceVariant = NothingCardElevated,
    onSurfaceVariant = NothingMuted,
    outline = NothingHairline,
    outlineVariant = NothingHairline
)

@Composable
fun NothingGlyphMapperTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NothingDarkColorScheme,
        typography = NothingTypography,
        content = content
    )
}
