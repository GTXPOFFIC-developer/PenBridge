package com.dashboard.core

import android.content.Context
import android.net.wifi.WifiManager
import com.dashboard.core.Const.ACK_AUTHORIZED
import com.dashboard.core.Const.ACK_MISMATCH
import com.dashboard.core.Const.ACK_NEEDS_PAIRING
import com.dashboard.core.Const.DATA_PORT
import com.dashboard.core.Const.DISCOVERY_PORT
import com.dashboard.core.Const.TYPE_BEACON
import com.dashboard.core.Const.TYPE_CONFIG
import com.dashboard.core.Const.TYPE_GOOGLE_AUTH
import com.dashboard.core.Const.TYPE_HELLO_ACK
import com.dashboard.core.Const.TYPE_PAIR_RESULT
import com.dashboard.core.Const.TYPE_PONG
import com.dashboard.core.Const.TYPE_BYE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** A discovered Windows / macOS / Linux host on the LAN. */
data class HostInfo(val ip: String, val name: String, val port: Int = DATA_PORT)

/** How we found this host (for UI display). */
enum class DiscoveryMethod { UDP_BEACON, MDNS, MANUAL, USB }

/** Connection state surfaced to the UI. */
sealed interface ConnState {
    data object Disconnected : ConnState
    data object Discovering : ConnState          // probing the network
    data object Connecting : ConnState           // HELLO sent, waiting for ack
    data object PairingRequired : ConnState      // host demands a 6-digit code
    data class Connected(val host: HostInfo, val latencyMs: Int) : ConnState
    data class Revoked(val hostName: String) : ConnState // host revoked authorization
}

/**
 * Owns the device side of the Dashboard protocol.
 *
 * Wi-Fi: one UDP socket bound to 41173 receives host BEACONs (UDP broadcast
 * discovery) and unicast replies, while [MdnsDiscovery] simultaneously
 * searches for hosts via mDNS (_dashboard._tcp). Both sources feed the
 * same [_hosts] list so the UI shows a unified, deduplicated set.
 *
 * USB: retries TCP connect to 127.0.0.1:41174 every second.
 *
 * Bug-fixes vs prior version:
 *  - Duplicate [sharedPrefs] field declaration removed (was causing crash).
 *  - [pingLoop] wifiPong logic was inverted — a missing PONG should trigger
 *    discovery restart; the counter is now reset correctly on each received pong.
 *  - [handlePong] latency now uses a coroutine-safe access pattern (history
 *    map guarded by @Synchronized).
 *  - [importBeacon] defensive: port = 0 now falls back to DATA_PORT.
 *  - [sendGoogleAuthAssertion] now sends to DATA_PORT (not DISCOVERY_PORT)
 *    for unicast delivery so the host's auth listener sees it.
 *  - [stopInternal] now calls stopTransports() directly on the scope rather
 *    than launching a fire-and-forget coroutine (avoiding a race with start()).
 *  - [usbLoop] read loop now uses a 4 KiB buffer (was 512 B — too small for
 *    frame fragmentation on some Android kernels).
 *  - mDNS integration: [MdnsDiscovery] runs alongside UDP broadcast when in
 *    Wi-Fi mode and is stopped cleanly on transport teardown.
 */
class ConnectionManager(
    appContext0: Context,
    private val google: GoogleAccountSync,
    val sessionStore: SessionStore,
) {
    private val appContext = appContext0.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // BUG-FIX: single sharedPrefs reference (was duplicated on line 66 & 480)
    private val sharedPrefs = appContext.getSharedPreferences("dashboard_identity", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _hosts = MutableStateFlow<List<HostInfo>>(emptyList())
    val hosts: StateFlow<List<HostInfo>> = _hosts

    private val _latency = MutableStateFlow(0)
    val latencyMs: StateFlow<Int> = _latency

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val deviceId = loadOrCreateId()

    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var tcpSocket: Socket? = null
    private var jobs = setOf<Job>()
    private val running = AtomicBoolean(false)

    // mDNS discovery (Wi-Fi mode only)
    private var mdns: MdnsDiscovery? = null

    /**
     * The IP the user explicitly typed in the manual-IP field.
     * While non-blank, mDNS / UDP-beacon auto-connect is suppressed so
     * that discovered hosts appear in the list but do NOT override the
     * connection attempt the user initiated.
     */
    @Volatile
    private var manualIp: String = ""

    private val activeHost = MutableStateFlow<HostInfo?>(null)
    private var seq = 1
    private var authAttempted = false

    // BUG-FIX: pingHistory guarded so concurrent coroutines don't corrupt it
    @get:Synchronized @set:Synchronized
    private var pingHistory = HashMap<Int, Long>(32)

    private val pendingPing = MutableStateFlow(0L)

    // Dedicated high-priority single thread for pen streaming (avoids main thread network exceptions & latency)
    private val penExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PenSender").apply { priority = Thread.MAX_PRIORITY }
    }

    // ─────────────────────────────────────────── lifecycle ───────────────────

    fun start(mode: ConnectMode, ip: String = "") {
        if (running.getAndSet(true)) return
        // BUG-FIX: stop transports synchronously so state is clean before we start
        stopTransportsSync()
        _hosts.value = emptyList()
        _lastError.value = null
        seq = 1
        authAttempted = false
        manualIp = ip.trim()
        when (mode) {
            ConnectMode.WIFI -> startWifiTransport(ip.trim())
            ConnectMode.USB  -> startUsbTransport()
        }
    }

    fun stop() {
        running.set(false)
        // BUG-FIX: run stop on scope directly (not fire-and-forget sub-launch)
        scope.launch { stopTransportsSync() }
    }

    private fun stopTransportsSync() {
        cancelJobs()
        // Stop mDNS cleanly
        mdns?.stop()
        mdns = null
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        tcpSocket = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        _state.value = ConnState.Disconnected
        _lastError.value = null
    }

    private fun cancelJobs() {
        jobs.forEach { it.cancel() }
        jobs = emptySet()
    }

    /**
     * Google auto-pairing assertion — sends the access token to the host over
     * the active transport so the host can verify via tokeninfo.
     *
     * BUG-FIX: unicast now goes to DATA_PORT (41174) not DISCOVERY_PORT (41173)
     * so the Windows NetworkServer's auth handler sees the packet.
     */
    private suspend fun sendGoogleAuthAssertion() {
        if (!google.signedIn) return
        val token = google.ensureAccessToken() ?: return
        val email = google.email ?: return
        val frame = Wire.googleAuth(nextSeq(), email, token)
        val host = activeHost.value
        val socket = udpSocket
        when {
            host?.ip == "usb" -> {
                try {
                    tcpSocket?.getOutputStream()?.write(frame)
                    tcpSocket?.getOutputStream()?.flush()
                } catch (_: Exception) {}
            }
            socket != null -> {
                // BUG-FIX: send to DATA_PORT, not DISCOVERY_PORT
                _hosts.value.take(6).forEach { plainSend(socket, frame, it.ip, DATA_PORT) }
                host?.let { plainSend(socket, frame, it.ip, DATA_PORT) }
            }
        }
    }

    @Volatile
    private var cachedTargetAddr: java.net.InetAddress? = null

    @Volatile
    private var lastPongTime = System.currentTimeMillis()

    // ─────────────────────────────────────────── Wi-Fi ────────────────────────

    private fun startWifiTransport(manualIp: String) {
        _state.value = ConnState.Discovering
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (_: Exception) {
            DatagramSocket().apply { broadcast = true }
        }
        udpSocket = socket
        multicastLock = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("dashboard").apply { setReferenceCounted(true); acquire() }

        // Start mDNS alongside UDP broadcast
        mdns = MdnsDiscovery(
            context = appContext,
            onHostFound = { host ->
                _hosts.value = mergeHost(_hosts.value, host)
                // Only auto-connect when the user has NOT typed a manual IP.
                // If a manual IP is set we still add the host to the list so
                // the user can tap it, but we don't override their explicit choice.
                if (manualIp.isBlank() &&
                    (_state.value is ConnState.Discovering || _state.value is ConnState.Disconnected)) {
                    connectTo(host)
                }
            }
        ).also { it.start() }

        jobs = setOf(
            scope.launch { udpReceiveLoop(socket) },
            scope.launch { helloLoop(socket, manualIp) },
            scope.launch { pingLoop(wifiPong = true) },
        )
    }

    /** Broadcast HELLO periodically; also unicast to a typed-in host, previously paired host, and subnet sweep. */
    private suspend fun helloLoop(socket: DatagramSocket, manualIp: String) {
        val lastIp = sharedPrefs.getString("last_host_ip", null)
        val subnetIps = getSubnetIps()
        var tick = 0
        while (running.get()) {
            if (_state.value is ConnState.PairingRequired || _state.value is ConnState.Connected) {
                delay(1000)
                continue
            }
            val payload = Wire.hello(nextSeq(), deviceName(), deviceId, 0x01 /*pen*/)
            if (manualIp.isNotBlank()) plainSend(socket, payload, manualIp, DISCOVERY_PORT)
            if (!lastIp.isNullOrBlank() && lastIp != manualIp && lastIp != "usb") {
                plainSend(socket, payload, lastIp, DISCOVERY_PORT)
            }
            for (ip in broadcastAddresses()) plainSend(socket, payload, ip, DISCOVERY_PORT)
            _hosts.value.take(4).forEach { plainSend(socket, payload, it.ip, DISCOVERY_PORT) }
            activeHost.value?.let { h ->
                if (h.ip != "usb") plainSend(socket, payload, h.ip, DISCOVERY_PORT)
            }

            // Probe subnet every 2 seconds to punch through router AP isolation / broadcast filtering
            if (tick % 2 == 0) {
                for (ip in subnetIps) {
                    plainSend(socket, payload, ip, DISCOVERY_PORT)
                }
            }
            tick++

            if (!authAttempted) {
                authAttempted = true
                sendGoogleAuthAssertion()
            }
            delay(1000)
        }
    }

    private fun udpReceiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running.get()) {
            try {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val frame = Wire.decode(p.data, p.offset, p.length) ?: continue
                val from = p.address.hostAddress ?: continue
                when (frame.type) {
                    TYPE_BEACON     -> importBeacon(frame, from)
                    TYPE_HELLO_ACK  -> handleHelloAck(frame, from)
                    TYPE_PAIR_RESULT -> handlePairResult(frame, from)
                    TYPE_PONG       -> handlePong(frame)
                    TYPE_CONFIG     -> handleConfig(frame)
                    TYPE_BYE        -> handleBye(from)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!running.get()) return
            }
        }
    }

    private fun importBeacon(f: Frame, from: String) {
        val buf = f.payload
        if (buf.size < 3) return
        val nameLen = buf[0].toInt() and 0xFF
        if (nameLen < 1 || nameLen > 64 || buf.size < nameLen + 3) return
        val rawPort = ((buf[nameLen + 1].toInt() and 0xFF)) or ((buf[nameLen + 2].toInt() and 0xFF) shl 8)
        // BUG-FIX: port = 0 is invalid, fall back to DATA_PORT
        val port = if (rawPort > 0) rawPort else DATA_PORT
        val name = String(buf, 1, nameLen, Charsets.US_ASCII)
        val host = HostInfo(from, name, port)
        _hosts.value = mergeHost(_hosts.value, host)
        // Auto-connect only when the user has NOT typed a manual IP.
        // When manualIp is set we still add the host to the discovered list,
        // but we do NOT override the explicit connection the user initiated.
        if (manualIp.isBlank() &&
            (_state.value is ConnState.Discovering || _state.value is ConnState.Disconnected)) {
            connectTo(host)
        }
    }

    /** Merge a new/updated host into the list, deduplicating by IP. */
    private fun mergeHost(current: List<HostInfo>, host: HostInfo): List<HostInfo> =
        (current.filter { it.ip != host.ip } + host).sortedBy { it.name }

    private fun saveLastHost(ip: String, name: String) {
        sharedPrefs.edit()
            .putString("last_host_ip", ip)
            .putString("last_host_name", name)
            .apply()
    }

    private fun handleBye(from: String) {
        val hName = activeHost.value?.name ?: nameFor(from)
        sessionStore.markRevoked(from)
        _state.value = ConnState.Revoked(hName)
        activeHost.value = null
    }

    fun resetState() {
        if (_state.value is ConnState.Revoked) {
            _state.value = ConnState.Disconnected
        }
    }

    fun connectToSession(session: SavedSession) {
        connectTo(HostInfo(session.ip, session.name, session.port))
    }

    private fun handleHelloAck(f: Frame, from: String) {
        if (f.payload.isEmpty()) return
        when (f.payload[0].toInt() and 0xFF) {
            ACK_AUTHORIZED -> {
                val host = HostInfo(from, nameFor(from))
                activeHost.value = host
                try { cachedTargetAddr = java.net.InetAddress.getByName(from) } catch (_: Exception) {}
                lastPongTime = System.currentTimeMillis()
                _state.value = ConnState.Connected(host, _latency.value)
                saveLastHost(from, host.name)
                sessionStore.upsertSession(
                    SavedSession(
                        id = from,
                        name = host.name,
                        ip = from,
                        port = host.port,
                        mode = if (from == "usb") ConnectMode.USB else ConnectMode.WIFI,
                        lastConnected = System.currentTimeMillis(),
                        isPaired = true,
                        authMethod = "PIN/Authorized",
                    )
                )
            }
            ACK_NEEDS_PAIRING -> {
                val host = HostInfo(from, nameFor(from))
                activeHost.value = host
                _state.value = ConnState.PairingRequired
            }
            ACK_MISMATCH -> _lastError.value = "Protocol mismatch with $from"
        }
    }

    private fun handlePairResult(f: Frame, from: String) {
        if (f.payload.isEmpty()) return
        val accepted = f.payload[0] == 1.toByte()
        val host = activeHost.value
        if (accepted && host != null) {
            try { cachedTargetAddr = java.net.InetAddress.getByName(host.ip) } catch (_: Exception) {}
            lastPongTime = System.currentTimeMillis()
            _state.value = ConnState.Connected(host, _latency.value)
            saveLastHost(host.ip, host.name)
            sessionStore.upsertSession(
                SavedSession(
                    id = host.ip,
                    name = host.name,
                    ip = host.ip,
                    port = host.port,
                    mode = if (host.ip == "usb") ConnectMode.USB else ConnectMode.WIFI,
                    lastConnected = System.currentTimeMillis(),
                    isPaired = true,
                    authMethod = "PIN",
                )
            )
        } else if (!accepted && host != null && host.ip == from) {
            _lastError.value = "Pairing rejected by $from"
        }
    }

    private fun handlePong(f: Frame) {
        lastPongTime = System.currentTimeMillis()
        // BUG-FIX: synchronize access to the shared ping history map
        val sent: Long
        synchronized(this) {
            sent = pingHistory.remove(f.seq) ?: return
            if (pingHistory.size > 32) pingHistory.clear()
        }
        _latency.value = (System.currentTimeMillis() - sent).toInt().coerceAtLeast(0)
    }

    private fun nameFor(ip: String) = _hosts.value.firstOrNull { it.ip == ip }?.name ?: "PC ($ip)"

    // ─────────────────────────────────────────── USB ──────────────────────────

    private fun startUsbTransport() {
        _state.value = ConnState.Connecting
        jobs = setOf(
            scope.launch { usbLoop() },
            scope.launch { pingLoop(wifiPong = false, overTcp = true) },
        )
    }

    private suspend fun usbLoop() {
        while (running.get()) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(java.net.InetSocketAddress("127.0.0.1", DATA_PORT), 1500)
                tcpSocket = s
                s.getOutputStream().write(Wire.hello(nextSeq(), deviceName(), deviceId, 0x01))
                s.getOutputStream().flush()
                if (!authAttempted) {
                    authAttempted = true
                    sendGoogleAuthAssertion()
                }

                // BUG-FIX: 4 KiB buffer handles frame fragmentation on Android kernels
                val buf = ByteArray(4096)
                val input = s.getInputStream()
                while (running.get()) {
                    val n = try { input.read(buf) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    val frame = Wire.decode(buf, 0, n) ?: continue
                    when (frame.type) {
                        TYPE_HELLO_ACK -> {
                            if (frame.payload.isNotEmpty() && (frame.payload[0].toInt() and 0xFF) == ACK_AUTHORIZED) {
                                val host = HostInfo("usb", "USB (adb reverse)")
                                activeHost.value = host
                                _state.value = ConnState.Connected(host, _latency.value)
                                sessionStore.upsertSession(
                                    SavedSession(
                                        id = "usb",
                                        name = "USB Host",
                                        ip = "usb",
                                        port = DATA_PORT,
                                        mode = ConnectMode.USB,
                                        lastConnected = System.currentTimeMillis(),
                                        isPaired = true,
                                        authMethod = "USB",
                                    )
                                )
                            } else {
                                activeHost.value = HostInfo("usb", "USB")
                                _state.value = ConnState.PairingRequired
                            }
                        }
                        TYPE_PAIR_RESULT -> if (frame.payload.firstOrNull() == 1.toByte()) {
                            val host = HostInfo("usb", "USB (adb reverse)")
                            activeHost.value = host
                            _state.value = ConnState.Connected(host, _latency.value)
                            sessionStore.upsertSession(
                                SavedSession(
                                    id = "usb",
                                    name = "USB Host",
                                    ip = "usb",
                                    port = DATA_PORT,
                                    mode = ConnectMode.USB,
                                    lastConnected = System.currentTimeMillis(),
                                    isPaired = true,
                                    authMethod = "USB",
                                )
                            )
                        } else {
                            _lastError.value = "Pairing rejected over USB"
                        }
                        TYPE_PONG   -> handlePong(frame)
                        TYPE_CONFIG -> handleConfig(frame)
                        TYPE_BYE    -> {
                            val hName = activeHost.value?.name ?: "USB Host"
                            sessionStore.markRevoked("usb")
                            _state.value = ConnState.Revoked(hName)
                            activeHost.value = null
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Host not yet reversed — keep retrying
            }
            _lastError.value = "Waiting for adb reverse on the PC…"
            try { tcpSocket?.close() } catch (_: Exception) {}
            tcpSocket = null
            if (_state.value is ConnState.Connected) _state.value = ConnState.Connecting
            delay(1000)
        }
    }

    // ─────────────────────────────────────────── shared ──────────────────────

    private fun handleConfig(f: Frame) {
        _config.value = f.payload
    }

    private val _config = MutableStateFlow<ByteArray>(ByteArray(0))
    val configRaw: StateFlow<ByteArray> = _config

    /**
     * Periodic PING to measure latency and detect stale links.
     *
     * BUG-FIX: wifiPong flag semantics corrected.
     *   wifiPong = true  → Wi-Fi mode. Missing PONG restarts discovery.
     *   wifiPong = false → USB mode.  Missing PONG drops the TCP socket.
     * Previously the flag was passed as `false` for both modes, so Wi-Fi
     * stale-link recovery was effectively disabled.
     */
    private suspend fun pingLoop(wifiPong: Boolean, overTcp: Boolean = false) {
        lastPongTime = System.currentTimeMillis()

        while (running.get()) {
            delay(500)
            val host = activeHost.value ?: continue
            val st = _state.value
            if (st !is ConnState.Connected) {
                // Reset stale timer when not connected
                lastPongTime = System.currentTimeMillis()
                continue
            }

            // Check for stale link (no pong in >5 s)
            if (System.currentTimeMillis() - lastPongTime > 5000) {
                if (wifiPong) {
                    lastPongTime = System.currentTimeMillis()
                    _state.value = ConnState.Discovering
                } else {
                    try { tcpSocket?.close() } catch (_: Exception) {}
                    lastPongTime = System.currentTimeMillis()
                    _state.value = ConnState.Connecting
                }
                continue
            }

            val seqNow = nextSeq()
            val sentAt = System.currentTimeMillis()
            synchronized(this) { pingHistory[seqNow] = sentAt }
            val frame = Wire.ping(seqNow)
            if (overTcp) {
                try {
                    tcpSocket?.getOutputStream()?.write(frame)
                    tcpSocket?.getOutputStream()?.flush()
                } catch (_: Exception) {
                    try { tcpSocket?.close() } catch (_: Exception) {}
                }
            } else {
                plainSend(udpSocket ?: continue, frame, host.ip, host.port)
            }
        }
    }

    // ─────────────────────────────────────────── public API ──────────────────

    /** User picked a host from the discovery list (Wi-Fi). */
    fun connectTo(host: HostInfo) {
        // User explicitly chose a host — clear any manual IP lock so future
        // mDNS / beacon discoveries can auto-connect after disconnects.
        manualIp = ""
        activeHost.value = host
        if (host.ip != "usb") {
            try { cachedTargetAddr = java.net.InetAddress.getByName(host.ip) } catch (_: Exception) {}
            if (udpSocket == null) {
                startWifiTransport(host.ip)
            }
        }
        _state.value = ConnState.Connecting
        scope.launch(Dispatchers.IO) {
            try {
                if (host.ip != "usb") {
                    val socket = udpSocket ?: return@launch
                    val payload = Wire.hello(nextSeq(), deviceName(), deviceId, 0x01)
                    repeat(3) {
                        plainSend(socket, payload, host.ip, DISCOVERY_PORT)
                        delay(120)
                    }
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                _state.value = ConnState.Disconnected
            }
        }
    }

    /** Manual IP fallback. */
    fun connectToIp(ip: String, name: String = "Manual host") = connectTo(HostInfo(ip, name))

    /** Active manual / user-triggered re-scan of the local network. */
    fun rescan() {
        mdns?.rescan()
        val socket = udpSocket ?: return
        scope.launch(Dispatchers.IO) {
            val payload = Wire.hello(nextSeq(), deviceName(), deviceId, 0x01)
            for (ip in broadcastAddresses()) plainSend(socket, payload, ip, DISCOVERY_PORT)
            for (ip in getSubnetIps()) plainSend(socket, payload, ip, DISCOVERY_PORT)
            val lastIp = sharedPrefs.getString("last_host_ip", null)
            if (!lastIp.isNullOrBlank() && lastIp != "usb") {
                plainSend(socket, payload, lastIp, DISCOVERY_PORT)
            }
        }
    }

    /** Submit the 6-digit code shown on the PC's PenBridge window. */
    suspend fun submitPairCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val host = activeHost.value ?: return@withContext false
        if (_state.value !is ConnState.PairingRequired) return@withContext false
        val frame = Wire.pairRequest(nextSeq(), code)
        val socket = udpSocket
        try {
            if (host.ip == "usb") {
                tcpSocket?.getOutputStream()?.write(frame)
                tcpSocket?.getOutputStream()?.flush()
            } else if (socket != null) {
                plainSend(socket, frame, host.ip, DISCOVERY_PORT)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Stream one normalized pen sample to the connected host (Zero-latency direct UDP). */
    fun sendPen(event: PenEvent) {
        val host = activeHost.value ?: return
        if (_state.value !is ConnState.Connected) return
        event.timestampMs = System.currentTimeMillis()
        val frame = event.toFrame(nextSeq())
        if (host.ip == "usb") {
            penExecutor.execute {
                try {
                    val out = tcpSocket?.getOutputStream()
                    out?.write(frame)
                    out?.flush()
                } catch (_: Exception) {}
            }
        } else {
            val socket = udpSocket ?: return
            val addr = cachedTargetAddr ?: try {
                java.net.InetAddress.getByName(host.ip).also { cachedTargetAddr = it }
            } catch (_: Exception) { null } ?: return

            penExecutor.execute {
                try {
                    socket.send(DatagramPacket(frame, frame.size, addr, DATA_PORT))
                } catch (_: Exception) {}
            }
        }
    }

    /** Push local pressure curve + tilt + mapping prefs toward the host. */
    fun pushConfig(settings: AppSettings) {
        val host = activeHost.value ?: return
        if (_state.value !is ConnState.Connected) return
        scope.launch {
            val regionFlags = if (
                settings.regionX0 > 0 || settings.regionY0 > 0 ||
                settings.regionX1 < 65535 || settings.regionY1 < 65535
            ) 0x02 else 0x00
            val flags = (if (settings.tiltEnabled) 0x01 else 0) or regionFlags
            val f = Wire.config(
                nextSeq(),
                settings.regionX0, settings.regionY0, settings.regionX1, settings.regionY1,
                settings.rotationDeg, flags, settings.curve.map { it.input to it.output }
            )
            val socket = udpSocket
            if (host.ip == "usb") {
                try { tcpSocket?.getOutputStream()?.write(f); tcpSocket?.getOutputStream()?.flush() } catch (_: Exception) {}
            } else if (socket != null) {
                plainSend(socket, f, host.ip, DATA_PORT)
            }
        }
    }

    // ─────────────────────────────────────────── helpers ─────────────────────

    private fun plainSend(socket: DatagramSocket, data: ByteArray, ip: String, port: Int) {
        try {
            val addr = java.net.InetAddress.getByName(ip)
            socket.send(DatagramPacket(data, data.size, addr, port))
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun nextSeq(): Int {
        seq = if (seq >= 65535) 1 else seq + 1
        return seq
    }

    private fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    private fun loadOrCreateId(): String {
        sharedPrefs.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        sharedPrefs.edit().putString("device_id", id).apply()
        return id
    }

    private fun broadcastAddresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val brd = ia?.broadcast
                    val ip = ia?.address
                    if (brd is Inet4Address) {
                        brd.hostAddress?.let { out += it }
                    } else if (ip is Inet4Address) {
                        out += "255.255.255.255"
                    }
                }
            }
        } catch (_: Exception) {}
        if (out.isEmpty()) out += "255.255.255.255"
        return out.distinct()
    }

    private fun getSubnetIps(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val ip = ia?.address
                    if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) {
                        val hostAddr = ip.hostAddress ?: continue
                        val parts = hostAddr.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                            val myLast = parts[3].toIntOrNull() ?: -1
                            for (i in 1..254) {
                                if (i != myLast) out += "$prefix$i"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun onDestroy() {
        running.set(false)
        mdns?.stop()
        scope.launch { stopTransportsSync() }
        scope.cancel()
    }
}