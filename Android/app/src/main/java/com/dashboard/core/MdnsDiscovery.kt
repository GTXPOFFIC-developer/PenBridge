package com.dashboard.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * mDNS / NSD-based host discovery with active periodic probing and sequential service resolution.
 *
 * Solves Android NSD limitations:
 * 1. Sequential Resolve Queue: NsdManager crashes/fails with FAILURE_ALREADY_ACTIVE (code 3)
 *    if multiple services are resolved concurrently. We queue and resolve one at a time.
 * 2. Active Query Probing: Android NSD is passive and scans only once on startup. We send
 *    standard mDNS queries to 224.0.0.251:5353 periodically to wake up hosts on demand.
 * 3. Safe Re-scan: Safely stops and restarts NsdManager discovery without state race conditions.
 */
class MdnsDiscovery(
    context: Context,
    private val onHostFound: (HostInfo) -> Unit,
    private val onHostLost: (String) -> Unit = {},
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val channel = Channel<HostInfo>(Channel.UNLIMITED)
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val active = AtomicBoolean(false)
    private val isDiscovering = AtomicBoolean(false)

    @Volatile
    private var stopCallback: (() -> Unit)? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Start discovery failed: $errorCode")
            isDiscovering.set(false)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Stop discovery failed: $errorCode")
            isDiscovering.set(false)
        }
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "mDNS discovery started for $serviceType")
            isDiscovering.set(true)
        }
        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "mDNS discovery stopped")
            isDiscovering.set(false)
            val cb = stopCallback
            stopCallback = null
            cb?.invoke()
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!active.get()) return
            // Queue for sequential resolution to avoid FAILURE_ALREADY_ACTIVE
            resolveQueue.trySend(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
            onHostLost(serviceInfo.serviceName ?: "")
        }
    }

    fun start() {
        if (active.getAndSet(true)) return

        // 1. Start sequential resolver worker
        scope.launch {
            for (serviceInfo in resolveQueue) {
                if (!active.get()) break
                resolveServiceSequentially(serviceInfo)
            }
        }

        // 2. Deliver resolved hosts to callback
        scope.launch {
            for (host in channel) {
                if (!active.get()) break
                onHostFound(host)
            }
        }

        // 3. Start NsdManager discovery
        safeStartDiscovery()

        // 4. Continuous periodic mDNS probe loop (probes every 3.5 seconds)
        scope.launch {
            while (isActive && active.get()) {
                sendRawMdnsQuery()
                delay(3500)
            }
        }
    }

    /** Trigger an immediate active scan and safe NsdManager refresh */
    fun rescan() {
        if (!active.get()) return
        scope.launch {
            sendRawMdnsQuery()
            safeRestartDiscovery()
        }
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        safeStopDiscovery()
        channel.close()
        resolveQueue.close()
        scope.cancel()
    }

    private fun safeStartDiscovery() {
        if (isDiscovering.get()) return
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS discovery: ${e.message}")
            isDiscovering.set(false)
        }
    }

    private fun safeStopDiscovery(onStopped: (() -> Unit)? = null) {
        if (!isDiscovering.get()) {
            onStopped?.invoke()
            return
        }
        try {
            stopCallback = onStopped
            nsd.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {
            isDiscovering.set(false)
            onStopped?.invoke()
        }
    }

    private suspend fun safeRestartDiscovery() {
        safeStopDiscovery()
        // Wait up to 1 second for discovery to stop cleanly
        var waitCount = 0
        while (isDiscovering.get() && waitCount < 10) {
            delay(100)
            waitCount++
        }
        delay(200)
        safeStartDiscovery()
    }

    private suspend fun resolveServiceSequentially(serviceInfo: NsdServiceInfo) {
        withTimeoutOrNull(2500) {
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${info.serviceName}: code $errorCode")
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress
                        val port = if (info.port > 0) info.port else Const.DATA_PORT
                        val friendlyName = try {
                            info.attributes["name"]?.let { String(it, Charsets.UTF_8) }
                                ?: info.serviceName ?: ip ?: "PC"
                        } catch (_: Exception) {
                            info.serviceName ?: ip ?: "PC"
                        }

                        if (ip != null) {
                            val host = HostInfo(ip = ip, name = friendlyName, port = port)
                            Log.d(TAG, "mDNS resolved: $friendlyName @ $ip:$port")
                            channel.trySend(host)
                        }
                        if (cont.isActive) cont.resume(Unit)
                    }
                }

                try {
                    nsd.resolveService(serviceInfo, listener)
                } catch (e: Exception) {
                    Log.w(TAG, "resolveService threw: ${e.message}")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    /**
     * Builds and sends a raw mDNS query packet (PTR query for _dashboard._tcp.local)
     * to 224.0.0.251:5353 over UDP. This wakes up listening hosts immediately.
     */
    private fun sendRawMdnsQuery() {
        try {
            val fqdnParts = listOf("_dashboard", "_tcp", "local")
            val stream = ByteArrayOutputStream(64)
            val dos = DataOutputStream(stream)

            // DNS Header: ID=0, Flags=0 (standard query), QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)

            // Question Section: QNAME
            for (part in fqdnParts) {
                val b = part.toByteArray(Charsets.US_ASCII)
                dos.writeByte(b.size)
                dos.write(b)
            }
            dos.writeByte(0) // Root label
            dos.writeShort(12) // QTYPE: PTR (12)
            dos.writeShort(1)  // QCLASS: IN (1)
            dos.flush()

            val bytes = stream.toByteArray()
            val socket = DatagramSocket()
            val group = InetAddress.getByName("224.0.0.251")
            val packet = DatagramPacket(bytes, bytes.size, group, 5353)
            socket.send(packet)
            socket.close()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MdnsDiscovery"
        const val SERVICE_TYPE = "_dashboard._tcp."
    }
}
