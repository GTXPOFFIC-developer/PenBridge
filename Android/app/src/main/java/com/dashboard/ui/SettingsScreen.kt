package com.dashboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.DashboardApp
import com.dashboard.core.ConnectMode
import com.dashboard.core.CurvePoint
import com.dashboard.core.MappingMode
import com.dashboard.ui.components.GlassCard
import com.dashboard.ui.components.GradientBackdrop
import com.dashboard.ui.theme.Ink
import com.dashboard.ui.theme.AccentGradients
import com.dashboard.ui.theme.accentPair
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Settings screen: mapping, pressure curve, inputs, accent. */
@Composable
fun SettingsScreen(app: DashboardApp, onBack: () -> Unit) {
    val settingsState by app.settings.settings.collectAsState()
    val conn = app.connection

    fun commit(s: com.dashboard.core.AppSettings) {
        app.settings.update { s }
        conn.pushConfig(s)
    }

    GradientBackdrop {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Ink.muted) }
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(16.dp))

            // ---- Pro Features & Stylus Mode (VirtualTablet Pro Equivalent) ----
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PRO UNLOCKED", style = MaterialTheme.typography.titleMedium, color = Color(0xFF10B981), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("No Ads · 100% Free & Open Source", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }
                Text("All premium VirtualTablet Pro features unlocked out of the box", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                SwitchRow("Stylus Mode (S-Pen Only)", settingsState.stylusOnly) { commit(settingsState.copy(stylusOnly = it)) }
                Text("100% rejects finger touches and palm contact when drawing", color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                SwitchRow("Pen Trail", settingsState.penTrail) { commit(settingsState.copy(penTrail = it)) }
                Text("Real-time visual ink trail under the stylus tip on tablet", color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                SwitchRow("Raw Input / OSU! Mode", settingsState.rawInputMode) { commit(settingsState.copy(rawInputMode = it)) }
                Text("Unfiltered 240Hz+ digitizer stream with zero lag for rhythm games", color = Ink.muted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(14.dp))

            // ---- Pen-Button Customization (Samsung S-Pen) ----------------------
            GlassCard {
                Text("Samsung S-Pen Button", style = MaterialTheme.typography.titleMedium)
                Text("Remap the action triggered when clicking the S-Pen barrel button", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Pill("Right Click", settingsState.barrelAction == com.dashboard.core.BarrelAction.RIGHT_CLICK) {
                        commit(settingsState.copy(barrelAction = com.dashboard.core.BarrelAction.RIGHT_CLICK))
                    }
                    Pill("Eraser", settingsState.barrelAction == com.dashboard.core.BarrelAction.ERASER) {
                        commit(settingsState.copy(barrelAction = com.dashboard.core.BarrelAction.ERASER))
                    }
                    Pill("Middle Click", settingsState.barrelAction == com.dashboard.core.BarrelAction.MIDDLE_CLICK) {
                        commit(settingsState.copy(barrelAction = com.dashboard.core.BarrelAction.MIDDLE_CLICK))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Pill("Double Click", settingsState.barrelAction == com.dashboard.core.BarrelAction.DOUBLE_CLICK) {
                        commit(settingsState.copy(barrelAction = com.dashboard.core.BarrelAction.DOUBLE_CLICK))
                    }
                    Pill("Undo (Ctrl+Z)", settingsState.barrelAction == com.dashboard.core.BarrelAction.UNDO) {
                        commit(settingsState.copy(barrelAction = com.dashboard.core.BarrelAction.UNDO))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---- Mapping area ------------------------------------------------
            GlassCard {
                Text("Drawing-Area Selection & Positioning", style = MaterialTheme.typography.titleMedium)
                Text("Map a specific portion of the tablet screen to your PC monitor", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Full Tablet", settingsState.mappingMode == MappingMode.FULL) { commit(settingsState.copy(mappingMode = MappingMode.FULL)) }
                    Pill("16:9 Screen", settingsState.mappingMode == MappingMode.CUSTOM && settingsState.regionX0 == 0 && settingsState.regionY0 == 7000) {
                        commit(settingsState.copy(mappingMode = MappingMode.CUSTOM, regionX0 = 0, regionY0 = 7000, regionX1 = 65535, regionY1 = 58535))
                    }
                    Pill("Top Half", settingsState.mappingMode == MappingMode.CUSTOM && settingsState.regionY1 == 32768) {
                        commit(settingsState.copy(mappingMode = MappingMode.CUSTOM, regionX0 = 0, regionY0 = 0, regionX1 = 65535, regionY1 = 32768))
                    }
                    Pill("Custom Crop", settingsState.mappingMode == MappingMode.CUSTOM) { commit(settingsState.copy(mappingMode = MappingMode.CUSTOM)) }
                }
                if (settingsState.mappingMode == MappingMode.CUSTOM) {
                    RectEditor(
                        region = Rect(
                            settingsState.regionX0 / 65535f,
                            settingsState.regionY0 / 65535f,
                            settingsState.regionX1 / 65535f,
                            settingsState.regionY1 / 65535f,
                        ),
                        locked = settingsState.aspectLock,
                        accent = accentPair(settingsState.accentIndex).first,
                    ) { r ->
                        commit(settingsState.copy(
                            regionX0 = (r.left * 65535).roundToInt().coerceIn(0, 65535),
                            regionY0 = (r.top * 65535).roundToInt().coerceIn(0, 65535),
                            regionX1 = (r.right * 65535).roundToInt().coerceIn(0, 65535),
                            regionY1 = (r.bottom * 65535).roundToInt().coerceIn(0, 65535),
                        ))
                    }
                }
                SwitchRow("Lock aspect ratio", settingsState.aspectLock) {
                    commit(settingsState.copy(aspectLock = it))
                }
                Text("Rotation", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 90, 180, 270).forEach { d ->
                        Pill("$d°", settingsState.rotationDeg == d) { commit(settingsState.copy(rotationDeg = d)) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---- Pressure curve ----------------------------------------------
            GlassCard {
                Text("Pressure curve", style = MaterialTheme.typography.titleMedium)
                Text("Lift points to shape how pressure maps — synced with the PC", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                CurveEditor(
                    points = settingsState.curve,
                    accent = accentPair(settingsState.accentIndex).first,
                ) { curve -> commit(settingsState.copy(curve = curve)) }
                androidx.compose.material3.TextButton(onClick = { commit(settingsState.copy(curve = com.dashboard.core.AppSettings.DEFAULT_CURVE)) }) {
                    Text("Reset curve", color = accentPair(settingsState.accentIndex).first)
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---- Input toggles ------------------------------------------------
            GlassCard {
                Text("Input Options", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Finger as brush", settingsState.fingerInput) { commit(settingsState.copy(fingerInput = it)) }
                SwitchRow("Palm rejection", settingsState.palmRejection) { commit(settingsState.copy(palmRejection = it)) }
                SwitchRow("Tilt reports", settingsState.tiltEnabled) { commit(settingsState.copy(tiltEnabled = it)) }
                SwitchRow("Haptic feedback", settingsState.haptics) { commit(settingsState.copy(haptics = it)) }
            }
            Spacer(Modifier.height(14.dp))

            // ---- Accent color -------------------------------------------------
            GlassCard {
                Text("Accent color", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentGradients.forEachIndexed { i, (c0, c1) ->
                        val selected = i == settingsState.accentIndex
                        Box(
                            Modifier
                                .size(if (selected) 34.dp else 28.dp)
                                .background(Brush.linearGradient(listOf(c0, c1)), CircleShape)
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) Color.White else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable(interactionSource = MutableInteractionSource(), indication = null) {
                                    commit(settingsState.copy(accentIndex = i))
                                },
                        )
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val a = accentPair(0).first
    Box(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (selected) a.copy(alpha = 0.85f) else Ink.surfaceHigh.copy(alpha = 0.5f))
            .border(1.dp, if (selected) a.copy(alpha = 0.6f) else Ink.stroke, RoundedCornerShape(100.dp))
            .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Color.White else Ink.muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink.text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accentPair(0).first,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Ink.surfaceHigh,
                uncheckedThumbColor = Ink.muted,
            ),
        )
    }
}

// --------------------------------------------------------------------------

/**
 * Drag editor for the mapped region. Behavior:
 *  - drag the body           -> move the rect
 *  - drag a corner handle    -> resize (aspect-locked around the opposite corner)
 */
@Composable
private fun RectEditor(
    region: Rect,
    locked: Boolean,
    accent: Color,
    onChange: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val space = 200f
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(region, locked) {
                detectDragGestures { change, _ ->
                    val p = Offset(change.position.x / size.width, change.position.y / size.height)
                    val r = region
                    // nearest feature: corners then body
                    val corners = listOf(
                        CornerFeature("tl", r.left, r.top, Offset(p.x, p.y)),
                        CornerFeature("tr", r.right, r.top, Offset(p.x, p.y)),
                        CornerFeature("bl", r.left, r.bottom, Offset(p.x, p.y)),
                        CornerFeature("br", r.right, r.bottom, Offset(p.x, p.y)),
                    )
                    val best = corners.minBy { it.dist }
                    val inBody = p.x > r.left && p.x < r.right && p.y > r.top && p.y < r.bottom
                    when {
                        best.dist < 0.09f -> {
                            // corner drag
                            var nr = when (best.name) {
                                "tl" -> Rect(best.hit.x, best.hit.y, r.right, r.bottom)
                                "tr" -> Rect(r.left, best.hit.y, best.hit.x, r.bottom)
                                "bl" -> Rect(r.left, r.top, best.hit.x, best.hit.y)
                                else -> Rect(r.left, r.top, best.hit.x, best.hit.y)
                            }
                            nr = clamp0(nr)
                            if (locked && nr.width > 0.02f && nr.height > 0.02f) {
                                val aspect = minOf(1f, nr.width / nr.height)
                                // keep width/height ratio tied to screen: use 16/10 approximation? use square-ish
                                val lock = 1f
                                val cx = (nr.left + nr.right) / 2f
                                val cy = (nr.top + nr.bottom) / 2f
                                val half = max(nr.width / 2f, nr.height / 2f)
                                nr = Rect(
                                    (cx - half * lock).coerceIn(0f, 1f),
                                    (cy - half).coerceIn(0f, 1f),
                                    (cx + half * lock).coerceIn(0f, 1f),
                                    (cy + half).coerceIn(0f, 1f),
                                )
                            }
                            onChange(nr)
                        }
                        inBody -> {
                            val dx = p.x - (r.left + r.right) / 2f
                            val dy = p.y - (r.top + r.bottom) / 2f
                            var nr = Rect(
                                (r.left + dx).coerceIn(0f, 1f),
                                (r.top + dy).coerceIn(0f, 1f),
                                (r.right + dx).coerceIn(0f, 1f),
                                (r.bottom + dy).coerceIn(0f, 1f),
                            )
                            if (r.width >= 0.95f) nr = Rect((r.width - 0.05f) * (1 - r.left) / r.width + 0f, r.top, r.left + 0.9f * r.width, r.bottom)
                            if (nr.right > 1f) nr = Rect(nr.left - (nr.right - 1f), nr.top, 1f, nr.bottom)
                            if (nr.left < 0f) nr = Rect(0f, nr.top, nr.right - nr.left, nr.bottom)
                            if (nr.bottom > 1f) nr = Rect(nr.left, nr.top - (nr.bottom - 1f), nr.right, 1f)
                            if (nr.top < 0f) nr = Rect(nr.left, 0f, nr.right, nr.bottom - nr.top)
                            onChange(nr)
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().background(Ink.surfaceHigh.copy(alpha = 0.35f), RoundedCornerShape(16.dp))) {
            val left = region.left * size.width
            val top = region.top * size.height
            val right = region.right * size.width
            val bottom = region.bottom * size.height
            drawRect(accent.copy(alpha = 0.12f), Offset(left, top), Size(right - left, bottom - top))
            drawRect(Brush.linearGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.9f))),
                Offset(left, top), Size(right - left, bottom - top), style = Stroke(2f))
            // inner cross
            drawLine(Color.White.copy(alpha = 0.25f), Offset(left, (top + bottom) / 2), Offset(right, (top + bottom) / 2), 1f)
            drawLine(Color.White.copy(alpha = 0.25f), Offset((left + right) / 2, top), Offset((left + right) / 2, bottom), 1f)
            // corner handles
            val hs = 8f
            listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach { c ->
                drawCircle(Color.White, hs, c)
                drawCircle(accent, hs - 3f, c)
            }
        }
    }
}

private fun clamp0(r: Rect): Rect {
    var nr = r
    if (nr.width < 0.02f) nr = Rect(nr.left, nr.top, nr.right + 0.02f, nr.bottom)
    if (nr.height < 0.02f) nr = Rect(nr.left, nr.top, nr.right, nr.bottom + 0.02f)
    return Rect(nr.left.coerceIn(0f, 1f), nr.top.coerceIn(0f, 1f), nr.right.coerceIn(0f, 1f), nr.bottom.coerceIn(0f, 1f))
}

private data class CornerFeature(val name: String, val x: Float, val y: Float, val p: Offset) {
    val dist get() = abs(x - p.x) + abs(y - p.y)
    val hit get() = Offset(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
}

// --------------------------------------------------------------------------

/**
 * Pressure response curve editor. Tap to add a control point (max 8),
 * long-press a point to remove it, drag to move.
 */
@Composable
private fun CurveEditor(
    points: List<CurvePoint>,
    accent: Color,
    onChange: (List<CurvePoint>) -> Unit,
) {
    val canvasH = 230f
    Box(
        Modifier.fillMaxWidth().height(canvasH.dp)
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { },
                    onDrag = { change, _ ->
                        val x = (change.position.x / size.width).coerceIn(0f, 1f) * 65535
                        val y = (1f - change.position.y / size.height).coerceIn(0f, 1f) * 65535
                        val idx = points.indexOfFirst { p ->
                            abs(p.input - x) < 0.08f * 65535 && abs(p.output - y) < 0.08f * 65535
                        }
                        val list = if (idx >= 0) points.toMutableList().apply { this[idx] = CurvePoint(x.roundToInt(), y.roundToInt()) }
                        else points.toMutableList().apply { add(CurvePoint(x.roundToInt(), y.roundToInt())) }
                        onChange(list.sortedBy { it.input }.take(8))
                    },
                    onDragEnd = {},
                )
            }
            .pointerInput(points, canvasH) {
                detectTapGestures(
                    onTap = { pos ->
                        // only single taps that are not on a point add new points
                        val x = (pos.x / size.width).coerceIn(0f, 1f) * 65535
                        val exists = points.any { abs(it.input - x) < 0.04f * 65535 }
                        if (!exists) {
                            val y = (1f - pos.y / size.height).coerceIn(0f, 1f) * 65535
                            onChange(points.toMutableList().apply { add(CurvePoint(x.roundToInt(), y.roundToInt())) }.sortedBy { it.input }.take(8))
                        }
                    },
                    onLongPress = { pos ->
                        val x = (pos.x / size.width).coerceIn(0f, 1f) * 65535
                        val y = (1f - pos.y / size.height).coerceIn(0f, 1f) * 65535
                        val filtered = points.filter { it != points.minByOrNull { q -> abs(q.input - x) + abs(q.output - y) } }
                        if (filtered.size >= 2) onChange(filtered.sortedBy { it.input })
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize().background(Ink.surfaceHigh.copy(alpha = 0.35f), RoundedCornerShape(16.dp))) {
            val w = size.width
            val h = size.height
            // axis gridlines
            for (i in 1 until 4) {
                val fx = w * i / 4f
                val fy = h * i / 4f
                drawLine(Color.White.copy(alpha = 0.05f), Offset(fx, 0f), Offset(fx, h), 1f)
                drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, fy), Offset(w, fy), 1f)
            }
            val pts = points.sortedBy { it.input }
            val path = Path()
            pts.forEachIndexed { i, p ->
                val x = p.input / 65535f * w
                val y = (1f - p.output / 65535f) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Brush.linearGradient(listOf(accent, Color.White)), style = Stroke(3f, cap = StrokeCap.Round))
            pts.forEach { p ->
                val x = p.input / 65535f * w
                val y = (1f - p.output / 65535f) * h
                drawCircle(Color.White, 10f, Offset(x, y))
                drawCircle(accent, 7f, Offset(x, y))
            }
        }
    }
}