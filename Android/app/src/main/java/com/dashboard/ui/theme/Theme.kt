package com.dashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.compositionLocalOf

val LocalAccent = compositionLocalOf { Pair(Color(0xFF8B5CF6), Color(0xFF4F46E5)) }

private val ColorScheme = darkColorScheme(
    primary = Color(0xFF8B5CF6),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF6366F1),
    onSecondary = Color(0xFFFFFFFF),
    background = Ink.bg,
    onBackground = Ink.text,
    surface = Ink.surface,
    onSurface = Ink.text,
    surfaceVariant = Ink.surfaceHigh,
    onSurfaceVariant = Ink.muted,
    error = Ink.danger,
)

@Composable
fun DashboardTheme(
    accent: Pair<Color, Color> = accentPair(0),
    content: @Composable () -> Unit,
) {
    // Dark theme always — the app is a "night studio" surface.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(
            colorScheme = ColorScheme.copy(primary = accent.first, secondary = accent.second),
            typography = Typography,
            content = content,
        )
    }
}