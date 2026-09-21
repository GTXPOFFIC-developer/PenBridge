package com.dashboard.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dashboard.core.ConnState
import com.dashboard.ui.theme.Ink
import com.dashboard.ui.theme.LocalAccent

/** Subtle glass panel with rounded corners, a fine border and an inner sheen. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val surface = if (onClick != null) Ink.surfaceHigh.copy(alpha = 0.55f) else Ink.surface.copy(alpha = 0.55f)
    var base = modifier
        .clip(shape)
        .background(Brush.linearGradient(listOf(surface, surface.copy(alpha = 0.35f))))
        .border(1.dp, Ink.stroke, shape)
    if (onClick != null) {
        base = base.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = onClick,
        )
    }
    Box(base) {
        Box(Modifier.matchParentSize().background(Ink.surfaceGlass))
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
fun GradientBackdrop(content: @Composable () -> Unit) {
    val (c0, c1) = LocalAccent.current
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(c0.copy(alpha = 0.20f), Color.Transparent)),
                radius = size.minDimension * 0.9f,
                center = Offset(size.width * 0.9f, size.height * 0.08f),
            )
            drawCircle(
                Brush.radialGradient(listOf(c1.copy(alpha = 0.15f), Color.Transparent)),
                radius = size.minDimension * 0.8f,
                center = Offset(size.width * 0.1f, size.height * 0.85f),
            )
        }
        content()
    }
}

/** Animated pen-nib logo: rotating halo rings around a pulsing nib. */
@Composable
fun AnimatedLogo(logoSize: Dp = 96.dp) {
    val (c0, c1) = LocalAccent.current
    val infinite = rememberInfiniteTransition(label = "logo")
    val rot by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "rot",
    )
    val pulse by infinite.animateFloat(
        0.92f, 1.08f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )

    Box(Modifier.size(logoSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { rotationZ = rot }
                .border(1.dp, c0.copy(alpha = 0.35f), CircleShape)
        )
        Box(
            Modifier.fillMaxSize().padding(logoSize * 0.16f)
                .graphicsLayer { rotationZ = -rot * 2f }
                .border(1.dp, c1.copy(alpha = 0.25f), CircleShape)
        )
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { val s = pulse; scaleX = s; scaleY = s }
                .background(Brush.linearGradient(listOf(c0, c1)), CircleShape)
        )
        Canvas(Modifier.size(logoSize * 0.55f)) {
            val s = size.width
            val nib = Path().apply {
                moveTo(s / 2f, s * 0.04f)
                lineTo(s * 0.9f, s * 0.66f)
                cubicTo(s * 0.9f, s * 0.84f, s * 0.74f, s * 0.92f, s / 2f, s * 0.96f)
                cubicTo(s * 0.26f, s * 0.92f, s * 0.1f, s * 0.84f, s * 0.1f, s * 0.66f)
                close()
            }
            drawPath(nib, Color.White)
            val shine = Path().apply {
                moveTo(s / 2f, s * 0.04f)
                lineTo(s * 0.62f, s * 0.5f)
                cubicTo(s / 2f, s * 0.58f, s * 0.38f, s * 0.42f, s * 0.38f, s * 0.42f)
                close()
            }
            drawPath(shine, c0.copy(alpha = 0.9f))
        }
    }
}

/** Live connection chip for the home screen. */
@Composable
fun StatusChip(state: ConnState) {
    val (label, color) = when (state) {
        is ConnState.Disconnected -> "Disconnected" to Ink.muted
        is ConnState.Discovering -> "Discovering…" to Ink.warn
        is ConnState.Connecting -> "Connecting…" to Ink.warn
        is ConnState.PairingRequired -> "Pairing required" to Ink.warn
        is ConnState.Connected -> "Connected · ${state.latencyMs} ms" to Ink.success
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(100.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}