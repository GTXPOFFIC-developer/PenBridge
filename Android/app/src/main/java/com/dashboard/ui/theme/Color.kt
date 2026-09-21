package com.dashboard.ui.theme

import androidx.compose.ui.graphics.Color

object Ink {
    val bg = Color(0xFF0B0E1A)
    val surface = Color(0xFF12162B)
    val surfaceHigh = Color(0xFF1A1F3D)
    val surfaceGlass = Color(0x14FFFFFF)
    val stroke = Color(0x2EFFFFFF)
    val text = Color(0xFFECEEFF)
    val muted = Color(0xFF8B93B8)
    val success = Color(0xFF34D399)
    val warn = Color(0xFFFBBF24)
    val danger = Color(0xFFF43F5E)
}

/**
 * Accent gradient pairs, selectable in Settings. index -> (start, end).
 */
val AccentGradients = listOf(
    Color(0xFF8B5CF6) to Color(0xFF4F46E5), // violet → indigo
    Color(0xFF22D3EE) to Color(0xFF3B82F6), // cyan → blue
    Color(0xFFF43F5E) to Color(0xFFFB923C), // rose → orange
    Color(0xFFFBBF24) to Color(0xFFF97316), // amber → orange
    Color(0xFF34D399) to Color(0xFF14B8A6), // emerald → teal
)

fun accentPair(index: Int): Pair<Color, Color> =
    AccentGradients[(index % AccentGradients.size + AccentGradients.size) % AccentGradients.size]

val BrushGradient = listOf(0.62f to Color(0xFFE0C3FC), 1f to Color(0xFF8EC5FC))