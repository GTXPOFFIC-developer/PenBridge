package com.dashboard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.DashboardApp
import com.dashboard.core.ConnectMode
import com.dashboard.core.ConnState
import com.dashboard.core.HostInfo
import com.dashboard.ui.components.AnimatedLogo
import com.dashboard.ui.components.GlassCard
import com.dashboard.ui.components.GradientBackdrop
import com.dashboard.ui.components.StatusChip
import com.dashboard.ui.theme.Ink
import com.dashboard.ui.theme.accentPair
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Bolt

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PowerSettingsNew
import com.dashboard.core.SavedSession

enum class Screen { Home, Draw, Settings }

/**
 * Home / Connect screen: mode toggle (USB | Wi-Fi), host discovery cards,
 * persistent saved sessions, manual IP fallback, Google account link and live status.
 */
@Composable
fun HomeScreen(
    app: DashboardApp,
    onOpenDraw: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val conn = app.connection
    val state by conn.state.collectAsState()
    val hosts by conn.hosts.collectAsState()
    val savedSessions by app.sessions.sessions.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val google = app.google

    var manualIp by remember { mutableStateOf(settings.hostIp) }
    var pairingCode by remember { mutableStateOf("") }
    val showPairing = state is ConnState.PairingRequired
    var autoNavigated by rememberSaveable { mutableStateOf(false) }

    // Auto-start connection on launch to remember old sessions / discover host
    LaunchedEffect(Unit) {
        if (conn.state.value is ConnState.Disconnected) {
            conn.start(settings.mode, settings.hostIp.takeIf { settings.mode == ConnectMode.WIFI } ?: "")
        }
    }

    // Auto-open drawing screen only on fresh connection transition (never trap the user on Home)
    LaunchedEffect(state) {
        if (state is ConnState.Connected && !autoNavigated) {
            autoNavigated = true
            onOpenDraw()
        } else if (state !is ConnState.Connected) {
            autoNavigated = false
        }
    }

    // Host session revocation notification
    if (state is ConnState.Revoked) {
        AlertDialog(
            onDismissRequest = { conn.resetState() },
            title = { Text("Session Revoked") },
            text = { Text("The host '${(state as ConnState.Revoked).hostName}' has revoked this tablet's authorization. Please pair again.") },
            confirmButton = {
                TextButton(onClick = { conn.resetState() }) {
                    Text("OK", color = accentPair(0).first, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Ink.surface,
            titleContentColor = Color.White,
            textContentColor = Ink.muted,
        )
    }

    GradientBackdrop {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                // Top bar -----------------------------------------------------
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimatedLogo(52.dp)
                        Column {
                            Text("PenBridge", style = MaterialTheme.typography.headlineMedium)
                            Text("Tablet → Virtual Digitizer", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = Ink.muted)
                    }
                }

                Spacer(Modifier.height(18.dp))
                StatusChip(state)
                Spacer(Modifier.height(18.dp))

                // Active Connected Banner
                if (state is ConnState.Connected) {
                    val h = (state as ConnState.Connected).host
                    GlassCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Connected: ${h.name}", style = MaterialTheme.typography.titleMedium, color = Ink.success)
                                Text("${h.ip} · Virtual Digitizer active", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { conn.stop() }) {
                                    Text("Disconnect", color = Ink.muted)
                                }
                                Button(
                                    onClick = onOpenDraw,
                                    colors = ButtonDefaults.buttonColors(containerColor = accentPair(0).first)
                                ) {
                                    Text("Open Canvas")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }

                // Mode toggle -------------------------------------------------
                ModeToggle(
                    current = settings.mode,
                    onSelect = { mode ->
                        app.settings.update { it.copy(mode = mode) }
                        conn.start(mode, settings.hostIp.takeIf { mode == ConnectMode.WIFI } ?: "")
                    },
                )

                Spacer(Modifier.height(18.dp))

                // Previous Sessions Panel
                if (savedSessions.isNotEmpty()) {
                    SavedSessionsPanel(
                        sessions = savedSessions,
                        currentState = state,
                        onConnect = { s -> conn.connectToSession(s) },
                        onRemove = { id -> app.sessions.removeSession(id) }
                    )
                    Spacer(Modifier.height(18.dp))
                }

                when (settings.mode) {
                    ConnectMode.WIFI -> WifiPanel(
                        hosts = hosts,
                        state = state,
                        manualIp = manualIp,
                        onManualChange = { manualIp = it },
                        onManualConnect = {
                            app.settings.update { it.copy(hostIp = manualIp.trim()) }
                            conn.stop()
                            conn.start(ConnectMode.WIFI, manualIp.trim())
                        },
                        onHostTap = { conn.connectTo(it) },
                        onRescan = { conn.rescan() },
                    )
                    ConnectMode.USB -> UsbPanel(
                        state = state,
                        onReconnect = { conn.start(ConnectMode.USB) },
                    )
                }

                Spacer(Modifier.height(18.dp))

                GoogleCard(
                    google = google,
                    onSignedIn = onSignedIn,
                    scope = scope,
                )

                Spacer(Modifier.height(26.dp))

                OpenDrawButton(enabled = state is ConnState.Connected, onClick = onOpenDraw)
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showPairing) {
        AlertDialog(
            containerColor = Ink.surface,
            titleContentColor = Ink.text,
            textContentColor = Ink.muted,
            onDismissRequest = { conn.stop() },
            title = { Text("Pair with host") },
            text = {
                Column {
                    Text("Enter the 6-digit PIN shown on PC, or your configured host password.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it },
                        singleLine = true,
                        placeholder = { Text("PIN or Password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = txtColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { if (conn.submitPairCode(pairingCode)) pairingCode = "" }
                }) {
                    Text("Pair", color = accentPair(settings.accentIndex).first)
                }
            },
            dismissButton = {
                TextButton(onClick = { conn.stop() }) { Text("Cancel", color = Ink.muted) }
            },
        )
    }
}

@Composable
fun ModeToggle(current: ConnectMode, onSelect: (ConnectMode) -> Unit) {
    val a = accentPair(0)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ink.surfaceHigh.copy(alpha = 0.5f))
            .border(1.dp, Ink.stroke, RoundedCornerShape(20.dp)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeButton("USB", Icons.Default.Usb, current == ConnectMode.USB, a.first, Modifier.weight(1f)) { onSelect(ConnectMode.USB) }
        ModeButton("Wi-Fi", Icons.Default.Wifi, current == ConnectMode.WIFI, a.second, Modifier.weight(1f)) { onSelect(ConnectMode.WIFI) }
    }
}

@Composable
private fun ModeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (selected) Brush.horizontalGradient(listOf(accent, accent))
    else Brush.horizontalGradient(listOf(Ink.surface.copy(alpha = 0.3f), Ink.surface.copy(alpha = 0.3f)))
    Row(
        modifier.clip(shape).background(bg)
            .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Color.White else Ink.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            label,
            color = if (selected) Color.White else Ink.muted,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // animated presence
        if (selected) {
            Box(Modifier.size(6.dp).padding(start = 2.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun WifiPanel(
    hosts: List<HostInfo>,
    state: ConnState,
    manualIp: String,
    onManualChange: (String) -> Unit,
    onManualConnect: () -> Unit,
    onHostTap: (HostInfo) -> Unit,
    onRescan: () -> Unit = {},
) {
    GlassCard {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Discovered PCs", style = MaterialTheme.typography.titleMedium)
                    Text("mDNS + UDP broadcast", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onRescan) {
                    Icon(Icons.Default.Refresh, contentDescription = "Scan", tint = accentPair(0).first, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan", color = accentPair(0).first, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(10.dp))

            if (hosts.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = accentPair(0).first, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text(
                        when (state) {
                            is ConnState.Connecting -> "Contacting host…"
                            is ConnState.PairingRequired -> "Waiting to pair…"
                            else -> "Searching the network for host PCs…"
                        },
                        color = Ink.muted,
                    )
                }
            } else {
                hosts.forEach { h ->
                    val isConnected = state is ConnState.Connected && state.host.ip == h.ip
                    val isConnecting = state is ConnState.Connecting
                    HostRow(h, isConnected = isConnected, isConnecting = isConnecting) { onHostTap(h) }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    GlassCard {
        Text("Manual IP", style = MaterialTheme.typography.titleMedium)
        Text("the fallback if broadcasting is blocked by your router", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = onManualChange,
                singleLine = true,
                placeholder = { Text("192.168.1.42") },
                modifier = Modifier.weight(1f),
                colors = txtColors(),
            )
            TextButton(onClick = onManualConnect) {
                Text("Connect", color = accentPair(0).first, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HostRow(h: HostInfo, isConnected: Boolean, isConnecting: Boolean, onClick: () -> Unit) {
    val (c0, _) = accentPair(0)
    val bg = if (isConnected) c0.copy(alpha = 0.12f) else Ink.surface.copy(alpha = 0.5f)
    val stroke = if (isConnected) c0 else Ink.stroke

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, stroke),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(36.dp).background(c0.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bolt, null, tint = c0, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.titleMedium, color = Ink.text)
                Text(h.ip, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            }
            if (isConnecting) {
                CircularProgressIndicator(color = c0, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else if (isConnected) {
                Text("Connected", color = Ink.success, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            } else {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = c0.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Connect", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun UsbPanel(state: ConnState, onReconnect: () -> Unit) {
    val msg = when (state) {
        is ConnState.Connected -> "Connected · plug the pen in and draw"
        is ConnState.Connecting -> "Trying localhost:41174… keep the PC host running and unlock the tablet"
        else -> "Tap to connect. Make sure the PC host has started (it runs `adb reverse` for you)."
    }
    GlassCard {
        Text("USB over adb reverse", style = MaterialTheme.typography.titleMedium)
        Text(
            msg,
            color = Ink.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Button(
                onClick = onReconnect,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accentPair(0).first),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Usb, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("One-tap connect")
            }
            if (state is ConnState.Connecting) {
                CircularProgressIndicator(color = accentPair(0).first, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun GoogleCard(
    google: com.dashboard.core.GoogleAccountSync,
    onSignedIn: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val signedIn = google.signedIn
    val email = google.email
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    GlassCard(onClick = if (google.available) {
        {
            scope.launch {
                if (!signedIn) {
                    google.signIn { url -> uriHandler.openUri(url) }?.let { onSignedIn() }
                } else {
                    google.clear()
                }
            }
        }
    } else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(Brush.linearGradient(listOf(accentPair(0).first, accentPair(0).second)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (signedIn) (email?.firstOrNull()?.uppercase() ?: "G") else "G",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                when {
                    !google.available -> {
                        Text("Google link (optional)", style = MaterialTheme.typography.titleMedium)
                        Text("Add a GoogleConfig.clientId to auto-pair every PC you sign into", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    signedIn -> {
                        Text(email ?: "Signed in", style = MaterialTheme.typography.titleMedium)
                        Text("Auto-pair with your PCs — same account = trusted (tap to sign out)", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    else -> {
                        Text("Sign in with Google", style = MaterialTheme.typography.titleMedium)
                        Text("Link your account so PCs signed in as you auto-accept this tablet", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (google.available && !signedIn) {
                Icon(Icons.Default.Bolt, null, tint = accentPair(0).first, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun OpenDrawButton(enabled: Boolean, onClick: () -> Unit) {
    val (c0, c1) = accentPair(0)
    val shape = RoundedCornerShape(22.dp)
    val colors = if (enabled) Brush.horizontalGradient(listOf(c0, c1)) else Brush.horizontalGradient(listOf(Ink.surfaceHigh, Ink.surfaceHigh))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors)
            .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Brush, null, tint = if (enabled) Color.White else Ink.muted)
        Spacer(Modifier.size(10.dp))
        Text(
            if (enabled) "Open drawing surface" else "Connect a host to start drawing",
            color = if (enabled) Color.White else Ink.muted,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun txtColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accentPair(0).first,
    unfocusedBorderColor = Ink.stroke,
    focusedTextColor = Ink.text,
    unfocusedTextColor = Ink.text,
    focusedPlaceholderColor = Ink.muted.copy(alpha = 0.6f),
    unfocusedPlaceholderColor = Ink.muted.copy(alpha = 0.6f),
    cursorColor = accentPair(0).first,
)

@Composable
private fun SavedSessionsPanel(
    sessions: List<SavedSession>,
    currentState: ConnState,
    onConnect: (SavedSession) -> Unit,
    onRemove: (String) -> Unit,
) {
    GlassCard {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.History, "History", tint = accentPair(0).first, modifier = Modifier.size(18.dp))
                    Column {
                        Text("Saved Sessions", style = MaterialTheme.typography.titleMedium)
                        Text("Previously paired & connected hosts", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            sessions.forEach { s ->
                val isConnected = currentState is ConnState.Connected && (currentState.host.ip == s.ip || (s.ip == "usb" && currentState.host.ip == "usb"))
                SavedSessionRow(
                    session = s,
                    isConnected = isConnected,
                    onConnect = { onConnect(s) },
                    onRemove = { onRemove(s.id) }
                )
            }
        }
    }
}

@Composable
private fun SavedSessionRow(
    session: SavedSession,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val (c0, _) = accentPair(0)
    val bg = if (isConnected) c0.copy(alpha = 0.12f) else Ink.surface.copy(alpha = 0.5f)
    val stroke = if (isConnected) c0 else Ink.stroke

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, stroke),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (session.mode == ConnectMode.USB) Icons.Default.Usb else Icons.Default.Wifi,
                    null,
                    tint = if (isConnected) c0 else Ink.muted,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(session.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${session.ip} · ${if (session.isPaired) "Paired" else "Unpaired"}",
                        color = Ink.muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isConnected) {
                    Text("Connected", color = Ink.success, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                } else {
                    TextButton(onClick = onConnect) {
                        Text("Connect", color = c0, fontWeight = FontWeight.SemiBold)
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Remove", tint = Ink.muted.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}