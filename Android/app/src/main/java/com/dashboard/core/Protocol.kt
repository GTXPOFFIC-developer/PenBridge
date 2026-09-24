package com.dashboard.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire protocol — mirrors PROTOCOL.md exactly.
 *
 * Frame:
 *   [0..4) magic "DASH"
 *   [4]    type
 *   [5]    flags
 *   [6..8) seq       (LE u16)
 *   [8..10) payloadLen (LE u16)
 *   [10..) payload
 */
object Const {
    val MAGIC = "DASH".toByteArray(Charsets.US_ASCII)
    const val DISCOVERY_PORT = 41173
    const val DATA_PORT = 41174
    const val PROTOCOL_VERSION = 1

    // Packet types
    const val TYPE_HELLO = 0x01
    const val TYPE_BEACON = 0x02
    const val TYPE_HELLO_ACK = 0x03
    const val TYPE_PAIR_REQUEST = 0x04
    const val TYPE_PAIR_RESULT = 0x05
    const val TYPE_PEN_EVENT = 0x06
    const val TYPE_PING = 0x07
    const val TYPE_PONG = 0x08
    const val TYPE_BYE = 0x09
    const val TYPE_CONFIG = 0x0A
    const val TYPE_GOOGLE_AUTH = 0x0B

    // Flags
    const val FLAG_ACK = 0x01
    const val FLAG_FINAL = 0x02
    const val FLAG_PAIRING = 0x04

    // HELLO_ACK statuses
    const val ACK_AUTHORIZED = 0
    const val ACK_NEEDS_PAIRING = 1
    const val ACK_MISMATCH = 2

    // Pen actions
    const val ACTION_UP = 0
    const val ACTION_DOWN = 1
    const val ACTION_MOVE = 2
    const val ACTION_HOVER = 3
    const val ACTION_SCROLL = 4

    // Pen flags
    const val PEN_CONTACT = 0x01
    const val PEN_BARREL = 0x02
    const val PEN_ERASER = 0x04
    const val PEN_TILT = 0x08
    const val PEN_RELATIVE = 0x10
    const val PEN_MIDDLE = 0x20
    const val PEN_DOUBLE_CLICK = 0x40
    const val PEN_UNDO = 0x80

    val HEADER_SIZE = 10
}

/**
 * One decoded frame. [payload] is the raw body (may be empty).
 */
class Frame(
    val type: Int,
    val flags: Int,
    val seq: Int,
    val payload: ByteArray,
) {
    override fun toString() = "Frame(type=0x${type.toString(16)}, flags=0x${flags.toString(16)}, seq=$seq, len=${payload.size})"
}

object Wire {
    private val tmp = ByteBuffer.allocate(Const.HEADER_SIZE + 256).order(ByteOrder.LITTLE_ENDIAN)

    @Synchronized
    fun encode(type: Int, flags: Int, seq: Int, payload: ByteArray): ByteArray {
        tmp.clear()
        tmp.put(Const.MAGIC)
        tmp.put(type.toByte())
        tmp.put(flags.toByte())
        tmp.putShort(seq.toShort())
        tmp.putShort(payload.size.toShort())
        tmp.put(payload)
        return tmp.array().copyOf(Const.HEADER_SIZE + payload.size)
    }

    fun encode(type: Int, flags: Int, seq: Int): ByteArray =
        encode(type, flags, seq, ByteArray(0))

    /**
     * Parses a frame from [bytes]. Returns null when the buffer holds an
     * incomplete or foreign packet (kept short so spam from a busy network
     * drops at the first check, not after allocation).
     */
    fun decode(bytes: ByteArray, offset: Int, length: Int): Frame? {
        if (length < Const.HEADER_SIZE) return null
        var i = offset
        for (k in 0 until 4) {
            if (bytes[i++] != Const.MAGIC[k]) return null
        }
        val type = bytes[i++].toInt() and 0xFF
        val flags = bytes[i++].toInt() and 0xFF
        val seq = (bytes[i++].toInt() and 0xFF) or ((bytes[i++].toInt() and 0xFF) shl 8)
        val len = (bytes[i++].toInt() and 0xFF) or ((bytes[i++].toInt() and 0xFF) shl 8)
        if (len > length - Const.HEADER_SIZE) return null
        val payload = bytes.copyOfRange(offset + Const.HEADER_SIZE, offset + Const.HEADER_SIZE + len)
        return Frame(type, flags, seq, payload)
    }

    /* ---- Payload helpers ------------------------------------------------ */

    fun hello(seq: Int, name: String, deviceId: String, caps: Int): ByteArray {
        val id = deviceId.toByteArray(Charsets.US_ASCII)
        val n = name.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(1 + 1 + 1 + n.size + 1 + id.size + 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(Const.PROTOCOL_VERSION.toByte())
        buf.put(caps.toByte())
        buf.put(n.size.toByte())
        buf.put(n)
        buf.put(id.size.toByte())
        buf.put(id)
        buf.putShort(0) // deviceUdpPort: we only connect out
        return encode(Const.TYPE_HELLO, 0, seq, buf.array())
    }

    fun beacon(seq: Int, name: String, port: Int): ByteArray {
        val n = name.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(1 + n.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(n.size.toByte())
        buf.put(n)
        buf.putShort(port.toShort())
        return encode(Const.TYPE_BEACON, 0, seq, buf.array())
    }

    fun ping(seq: Int) = encode(Const.TYPE_PING, 0, seq)
    fun pong(seq: Int) = encode(Const.TYPE_PONG, Const.FLAG_ACK, seq)
    fun bye(seq: Int) = encode(Const.TYPE_BYE, 0, seq)

    fun helloAck(seq: Int, status: Int, final: Boolean): ByteArray =
        encode(Const.TYPE_HELLO_ACK, FLAG_OF(final), seq, byteArrayOf(status.toByte()))

    private fun FLAG_OF(final: Boolean) = if (final) Const.FLAG_FINAL else 0

    fun pairRequest(seq: Int, code: String): ByteArray {
        val c = code.trim().toByteArray(Charsets.UTF_8)
        return encode(Const.TYPE_PAIR_REQUEST, Const.FLAG_PAIRING, seq, c)
    }

    fun pairResult(seq: Int, accepted: Boolean): ByteArray =
        encode(Const.TYPE_PAIR_RESULT, 0, seq, byteArrayOf(if (accepted) 1 else 0))

    /**
     * Google account identity assertion (device -> host). The host verifies
     * [accessToken] with Google's tokeninfo endpoint and auto-authorizes
     * when the email matches the account the PC is signed into.
     */
    fun googleAuth(seq: Int, email: String, accessToken: String): ByteArray {
        val em = email.toByteArray(Charsets.US_ASCII)
        val tk = accessToken.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(2 + em.size + 2 + tk.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(em.size.toShort())
        buf.put(em)
        buf.putShort(tk.size.toShort())
        buf.put(tk)
        return encode(Const.TYPE_GOOGLE_AUTH, 0, seq, buf.array())
    }

    fun config(seq: Int, regionX0: Int, regionY0: Int, regionX1: Int, regionY1: Int,
               rotationDeg: Int, flags: Int, curve: List<Pair<Int, Int>>): ByteArray {
        val n = curve.size.coerceIn(1, 16)
        val buf = ByteBuffer.allocate(8 + 2 + 1 + 1 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(regionX0.toShort()); buf.putShort(regionY0.toShort())
        buf.putShort(regionX1.toShort()); buf.putShort(regionY1.toShort())
        buf.putShort(rotationDeg.toShort())
        buf.put(flags.toByte())
        buf.put(n.toByte())
        for ((a, b) in curve) {
            buf.putShort(a.toShort()); buf.putShort(b.toShort())
        }
        return encode(Const.TYPE_CONFIG, 0, seq, buf.array())
    }
}

/**
 * A normalized pen sample. Coordinates are 0..65535 across the full tablet
 * surface; the Windows host applies display mapping.
 */
class PenEvent {
    var action: Int = Const.ACTION_HOVER
    var contact: Boolean = false
    var barrel: Boolean = false
    var eraser: Boolean = false
    var middle: Boolean = false
    var doubleClick: Boolean = false
    var undo: Boolean = false
    var tiltPresent: Boolean = false
    var relative: Boolean = false
    var tiltX: Int = 0      // centidegrees -900..900
    var tiltY: Int = 0
    var pressure: Int = 0   // 0..65535
    var xNorm: Int = 0      // 0..65535 (or relative dx in relative mode)
    var yNorm: Int = 0      // 0..65535 (or relative dy in relative mode)
    var timestampMs: Long = 0

    /** Static scratch used to keep the hot path allocation-free. */
    companion object {
        val pool = PenEvent()
    }

    fun toFrame(seq: Int): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(action.toByte())
        var f = 0
        if (contact) f = f or Const.PEN_CONTACT
        if (barrel) f = f or Const.PEN_BARREL
        if (eraser) f = f or Const.PEN_ERASER
        if (tiltPresent) f = f or Const.PEN_TILT
        if (relative) f = f or Const.PEN_RELATIVE
        if (middle) f = f or Const.PEN_MIDDLE
        if (doubleClick) f = f or Const.PEN_DOUBLE_CLICK
        if (undo) f = f or Const.PEN_UNDO
        buf.put(f.toByte())
        buf.putShort(tiltX.toShort())
        buf.putShort(tiltY.toShort())
        buf.putShort(pressure.toShort())
        buf.putShort(xNorm.toShort())
        buf.putShort(yNorm.toShort())
        buf.putLong(timestampMs)
        return Wire.encode(Const.TYPE_PEN_EVENT, 0, seq, buf.array())
    }
}