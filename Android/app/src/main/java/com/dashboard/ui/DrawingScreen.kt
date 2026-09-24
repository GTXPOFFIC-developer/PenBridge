package com.dashboard.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dashboard.DashboardApp
import com.dashboard.core.ConnState
import com.dashboard.core.InputMode
import com.dashboard.core.PenSurfaceView
import com.dashboard.ui.theme.Ink
import com.dashboard.ui.theme.accentPair

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home

/**
 * Fullscreen drawing surface. The pen-capable [PenSurfaceView] streams raw
 * normalized samples to the PC through the connection manager; Compose only
 * paints the guide grid / mapped-region frame behind it and the floating,
 * collapsible toolbar on top.
 */
@Composable
fun DrawingScreen(
    app: DashboardApp,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val conn = app.connection
    val context = LocalContext.current
    val state by conn.state.collectAsState()
    val settings by app.settings.settings.collectAsState()

    var expanded by remember { mutableStateOf(true) }
    val (c0, c1) = accentPair(settings.accentIndex)

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        // Guide layer (behind the transparent pen view) ------------------------
        Canvas(Modifier.fillMaxSize()) {
            // subtle pixel grid
            val step = 64f
            var x = step
            while (x < size.width) { drawLine(Color.White.copy(alpha = 0.03f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = step
            while (y < size.height) { drawLine(Color.White.copy(alpha = 0.03f), Offset(0f, y), Offset(size.width, y), 1f); y += step }

            if (settings.inputMode == InputMode.TABLET) {
                // mapped region frame for absolute digitizer mode
                val left = size.width * settings.regionX0 / 65535f
                val top = size.height * settings.regionY0 / 65535f
                val right = size.width * settings.regionX1 / 65535f
                val bottom = size.height * settings.regionY1 / 65535f
                drawRect(c0.copy(alpha = 0.05f), Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top))
                drawRect(Brush.linearGradient(listOf(c0.copy(alpha = 0.7f), c1.copy(alpha = 0.7f))),
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(
                        (right - left).coerceAtLeast(0.1f),
                        (bottom - top).coerceAtLeast(0.1f),
                    ))
            }
        }

        // Pen capture view -----------------------------------------------------
        var penView by remember { mutableStateOf<PenSurfaceView?>(null) }
        AndroidView(
            factory = { ctx ->
                PenSurfaceView(
                    ctx,
                    onSample = { sample ->
                        conn.sendPen(sample)
                        if (sample.action == com.dashboard.core.Const.ACTION_DOWN && app.settings.settings.value.haptics) {
                            app.vibrate(10)
                        }
                    },
                    onContactChanged = { },
                ).also {
                    penView = it
                }
            },
            update = { view ->
                penView = view
                view.inputMode = settings.inputMode
                view.sensitivity = settings.trackpadSensitivity
                view.fingerEnabled = settings.fingerInput
                view.palmRejection = settings.palmRejection
                view.stylusOnly = settings.stylusOnly
                view.rawInputMode = settings.rawInputMode
                view.tiltEnabled = settings.tiltEnabled
                view.hapticsEnabled = settings.haptics
                view.barrelAction = settings.barrelAction
                view.penTrailEnabled = settings.penTrail
                view.hoverTrailEnabled = settings.hoverTrail
                view.trailColor = android.graphics.Color.argb(
                    (c0.alpha * 255).toInt(),
                    (c0.red * 255).toInt(),
                    (c0.green * 255).toInt(),
                    (c0.blue * 255).toInt(),
                )
                view.regionActive = settings.mappingMode == com.dashboard.core.MappingMode.CUSTOM
                view.regionX0 = settings.regionX0
                view.regionY0 = settings.regionY0
                view.regionX1 = settings.regionX1
                view.regionY1 = settings.regionY1
                view.onHaptic = { app.vibrate(8) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Floating collapsible toolbar ----------------------------------------
        Toolbar(
            expanded = expanded,
            state = state,
            accent = c0,
            onToggleExpand = { expanded = !expanded },
            onExit = onExit,
            onOpenSettings = onOpenSettings,
            inputMode = settings.inputMode,
            onToggleMode = {
                val nextMode = if (settings.inputMode == InputMode.TRACKPAD)
                    InputMode.TABLET
                else
                    InputMode.TRACKPAD
                app.settings.update { it.copy(inputMode = nextMode) }
            },
            finger = settings.fingerInput,
            onFinger = { app.settings.update { it.copy(fingerInput = it.fingerInput.not()) }; conn.pushConfig(app.settings.update { it.copy() }) },
            palm = settings.palmRejection,
            onPalm = { app.settings.update { it.copy(palmRejection = it.palmRejection.not()) }; conn.pushConfig(app.settings.update { it.copy() }) },
            tilt = settings.tiltEnabled,
            onTilt = { app.settings.update { it.copy(tiltEnabled = it.tiltEnabled.not()) }; conn.pushConfig(app.settings.update { it.copy() }) },
            stylusOnly = settings.stylusOnly,
            onStylusOnly = { app.settings.update { it.copy(stylusOnly = !it.stylusOnly) } },
            penTrail = settings.penTrail,
            onPenTrail = { app.settings.update { it.copy(penTrail = !it.penTrail) } },
            rawInput = settings.rawInputMode,
            onRawInput = { app.settings.update { it.copy(rawInputMode = !it.rawInputMode) } },
        )

        // "not connected" hint --------------------------------------------------
        AnimatedVisibility(visible = state !is ConnState.Connected, enter = fadeIn(), exit = fadeOut()) {
            val msg = when (state) {
                is ConnState.Connected -> ""
                is ConnState.PairingRequired -> "Pairing…"
                is ConnState.Connecting -> "Connecting…"
                else -> "Not connected — strokes won't reach the PC"
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)) {
                Text(
                    msg,
                    color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(Ink.surface.copy(alpha = 0.75f))
                        .border(1.dp, Ink.stroke, RoundedCornerShape(100.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Toolbar(
    expanded: Boolean,
    state: ConnState,
    accent: Color,
    onToggleExpand: () -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    inputMode: InputMode,
    onToggleMode: () -> Unit,
    finger: Boolean,
    onFinger: () -> Unit,
    palm: Boolean,
    onPalm: () -> Unit,
    tilt: Boolean,
    onTilt: () -> Unit,
    stylusOnly: Boolean,
    onStylusOnly: () -> Unit,
    penTrail: Boolean,
    onPenTrail: () -> Unit,
    rawInput: Boolean,
    onRawInput: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = expanded,
            enter = scaleIn(initialScale = 0.9f, animationSpec = tween(180)) + fadeIn(),
            exit = scaleOut(targetScale = 0.9f, animationSpec = tween(120)) + fadeOut(),
        ) {
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Ink.surface.copy(alpha = 0.82f))
                    .border(1.dp, Ink.stroke, RoundedCornerShape(22.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onExit) { Icon(Icons.Default.ArrowBack, "Menu", tint = Ink.muted) }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings", tint = Ink.muted) }
                val isTrackpad = inputMode == InputMode.TRACKPAD
                ToggleChip(if (isTrackpad) "🖱️ Trackpad" else "✏️ Tablet", true, accent, onToggleMode)
                ToggleChip("Stylus Only", stylusOnly, accent, onStylusOnly)
                ToggleChip("Trail", penTrail, accent, onPenTrail)
                if (rawInput) {
                    ToggleChip("⚡ OSU!", true, Color(0xFFEF4444), onRawInput)
                } else {
                    ToggleChip("Raw/OSU", false, accent, onRawInput)
                }
                ToggleChip("Finger", finger, accent, onFinger)
                ToggleChip("Palm", palm, accent, onPalm)
                ToggleChip("Tilt", tilt, accent, onTilt)

                val latency = (state as? ConnState.Connected)?.latencyMs
                Text(
                    if (latency != null) "$latency ms" else "·",
                    color = if (latency != null) Ink.success else Ink.muted,
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = onToggleExpand) { Icon(Icons.Default.ExpandLess, "Collapse", tint = Ink.muted) }
            }
        }
        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(Modifier.padding(top = 12.dp)) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp))
                        .background(Ink.surface.copy(alpha = 0.8f))
                        .border(1.dp, Ink.stroke, RoundedCornerShape(18.dp)),
                ) { Icon(Icons.Default.ExpandMore, "Expand", tint = Ink.muted) }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    val bg = if (on) accent.copy(alpha = 0.9f) else Ink.surfaceHigh.copy(alpha = 0.4f)
    Row(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bg)
            .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (on) Color.White else Ink.muted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}