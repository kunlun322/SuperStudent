package com.superstudent.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SsColorScheme = lightColorScheme(
    primary = SsColors.Primary,
    onPrimary = Color.White,
    primaryContainer = SsColors.PrimaryContainer,
    onPrimaryContainer = SsColors.Primary,
    secondary = SsColors.Accent,
    onSecondary = Color.White,
    background = SsColors.Background,
    onBackground = Color(0xFF243B2E),
    surface = SsColors.Surface,
    onSurface = Color(0xFF243B2E),
    surfaceVariant = SsColors.PrimaryContainer,
    onSurfaceVariant = Color(0xFF4A6154),
    error = SsColors.Error,
    onError = Color.White,
    outline = Color(0xFFC9DCCF),
)

private val SsTypography = Typography(
    headlineSmall = SsType.Title,
    titleLarge = SsType.Section,
    titleMedium = SsType.Section,
    bodyLarge = SsType.Body,
    bodyMedium = SsType.Body,
    labelLarge = SsType.Label,
    labelMedium = SsType.Label,
)

@Composable
fun SsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SsColorScheme,
        typography = SsTypography,
        content = content,
    )
}
