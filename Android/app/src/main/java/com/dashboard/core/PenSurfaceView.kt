package com.dashboard.core

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-level pen/touch capture surface.
 *
 * A plain [View] (rather than Compose pointer APIs) so we can:
 *  - call [requestUnbufferedDispatch] so the OS streams every frame,
 *  - drain [MotionEvent.getHistorical*] batches (no points dropped),
 *  - receive raw hover events via [onGenericMotionEvent].
 *
 * Every sample is normalized to the full tablet surface (0..65535) and
 * forwarded to [onSample] exactly as described in PROTOCOL.md.
 */
class PenSurfaceView(
    context: Context,
    private val onSample: (PenEvent) -> Unit,
    private val onContactChanged: (Boolean) -> Unit,
) : View(context) {

    /** Let fingers act as a brush. */
    var fingerEnabled: Boolean = false

    /** Drop simultaneous finger touches while a stylus is down. */
    var palmRejection: Boolean = true

    /** Read AXIS_TILT / AXIS_ORIENTATION and forward as tiltX/tiltY. */
    var tiltEnabled: Boolean = true

    /** Mode: Trackpad (relative mouse) vs Tablet (absolute digitizer). */
    var inputMode: InputMode = InputMode.TRACKPAD
    var sensitivity: Float = 1.3f
    var hapticsEnabled: Boolean = true
    var onHaptic: (() -> Unit)? = null

    // Trackpad relative tracking state
    private var lastTouchX = -1f
    private var lastTouchY = -1f
    private var subpixelX = 0f
    private var subpixelY = 0f
    private var downTime = 0L
    private var twoFingerScroll = false
    private var lastTwoFingerY = -1f
    private var pointerMoved = false

    private var penDown = false
    private var activePointerId = -1
    private var contactSent = false

    // Hover is lower priority; never drop contact events because of it.
    private var lastHoverNs = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestUnbufferedDispatch(event)
        if (inputMode == InputMode.TRACKPAD) {
            processTrackpad(event)
        } else {
            process(event, fromHistory = false)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (penDown) return false
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_ENTER
        ) {
            // Throttle hover (fine at ~60 Hz) while contact stays unthrottled.
            val now = event.eventTime
            if (now - lastHoverNs < 8_000_000L && !hoversChanged(event)) return true
            lastHoverNs = now
            val idx = event.actionIndex
            if (!toolAccepted(event.getToolType(idx))) return true
            val p = PenEvent.pool
            p.action = Const.ACTION_HOVER
            p.contact = false
            fill(p, event, idx, event.x, event.y)
            onSample(p)
        }
        return true // keep hover captured
    }

    private fun hoversChanged(e: MotionEvent) = e.historySize > 0

    private fun processTrackpad(event: MotionEvent) {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                lastTouchX = event.x
                lastTouchY = event.y
                subpixelX = 0f
                subpixelY = 0f
                pointerMoved = false
                twoFingerScroll = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerScroll = true
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerScroll && event.pointerCount >= 2) {
                    val currentY = (event.getY(0) + event.getY(1)) / 2f
                    val dy = currentY - lastTwoFingerY
                    if (Math.abs(dy) > 1f) {
                        pointerMoved = true
                        val scrollUnits = (dy * 6f).toInt()
                        if (scrollUnits != 0) {
                            val p = PenEvent.pool
                            p.action = Const.ACTION_SCROLL
                            p.relative = true
                            p.xNorm = 0
                            p.yNorm = scrollUnits
                            onSample(p)
                            lastTwoFingerY = currentY
                        }
                    }
                    return
                }

                if (lastTouchX < 0f || lastTouchY < 0f) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return
                }

                val rawDx = (event.x - lastTouchX) * sensitivity + subpixelX
                val rawDy = (event.y - lastTouchY) * sensitivity + subpixelY
                val intDx = rawDx.toInt()
                val intDy = rawDy.toInt()

                lastTouchX = event.x
                lastTouchY = event.y
                subpixelX = rawDx - intDx
                subpixelY = rawDy - intDy

                if (intDx != 0 || intDy != 0) {
                    pointerMoved = true
                    val p = PenEvent.pool
                    p.action = Const.ACTION_MOVE
                    p.relative = true
                    p.xNorm = intDx
                    p.yNorm = intDy
                    p.contact = false
                    onSample(p)
                }
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - downTime
                if (!pointerMoved && elapsed < 300) {
                    // Tap = Left Click!
                    val p = PenEvent.pool
                    p.action = Const.ACTION_DOWN
                    p.relative = true
                    p.contact = true
                    p.xNorm = 0
                    p.yNorm = 0
                    onSample(p)

                    p.action = Const.ACTION_UP
                    p.contact = false
                    onSample(p)

                    if (hapticsEnabled) onHaptic?.invoke()
                }

                lastTouchX = -1f
                lastTouchY = -1f
                subpixelX = 0f
                subpixelY = 0f
                twoFingerScroll = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFingerScroll) {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!pointerMoved && elapsed < 350) {
                        // Two-finger tap = Right Click!
                        val p = PenEvent.pool
                        p.action = Const.ACTION_DOWN
                        p.barrel = true
                        p.relative = true
                        p.xNorm = 0
                        p.yNorm = 0
                        onSample(p)
                        if (hapticsEnabled) onHaptic?.invoke()
                    }
                    twoFingerScroll = false
                    lastTouchX = -1f
                    lastTouchY = -1f
                    subpixelX = 0f
                    subpixelY = 0f
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTouchX = -1f
                lastTouchY = -1f
                twoFingerScroll = false
            }
        }
    }

    private fun process(event: MotionEvent, fromHistory: Boolean) {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val idx = event.actionIndex
                if (!toolAccepted(event.getToolType(idx))) return
                activePointerId = event.getPointerId(idx)
                penDown = true
                if (!contactSent) { onContactChanged(true); contactSent = true }
                if (hapticsEnabled) onHaptic?.invoke()
                val p = PenEvent.pool
                p.action = Const.ACTION_DOWN
                p.contact = true
                fill(p, event, idx, event.x, event.y)
                onSample(p)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!penDown) return
                val idx = indexOfPointer(event, activePointerId)
                if (idx < 0) return
                // Historical frames first, newest last — order matters on the PC.
                val historyCount = event.historySize
                for (h in 0 until historyCount) {
                    val p = PenEvent.pool
                    p.action = Const.ACTION_MOVE
                    p.contact = true
                    fill(p, event, idx, event.getHistoricalX(idx, h), event.getHistoricalY(idx, h), h)
                    onSample(p)
                }
                val p = PenEvent.pool
                p.action = Const.ACTION_MOVE
                p.contact = true
                fill(p, event, idx, event.x, event.y)
                onSample(p)
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP -> {
                if (!penDown) return
                val idx = event.actionIndex
                val p = PenEvent.pool
                p.action = Const.ACTION_UP
                p.contact = false
                fill(p, event, idx, event.x, event.y)
                onSample(p)
                penDown = false
                activePointerId = -1
                if (contactSent) { onContactChanged(false); contactSent = false }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!penDown) return
                val p = PenEvent.pool
                p.action = Const.ACTION_UP
                p.contact = false
                fill(p, event, event.actionIndex, event.x, event.y)
                onSample(p)
                penDown = false
                activePointerId = -1
                if (contactSent) { onContactChanged(false); contactSent = false }
            }
        }
    }

    private fun toolAccepted(tool: Int): Boolean {
        return when (tool) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            else -> fingerEnabled && !(palmRejection && penDown)
        }
    }

    private fun indexOfPointer(e: MotionEvent, id: Int): Int {
        for (i in 0 until e.pointerCount) if (e.getPointerId(i) == id) return i
        return -1
    }

    private fun fill(
        p: PenEvent,
        e: MotionEvent,
        idx: Int,
        rawX: Float,
        rawY: Float,
        historyPos: Int = -1,
    ) {
        val w = if (width > 0) width else 1
        val h = if (height > 0) height else 1
        p.xNorm = ((rawX / w) * 65535f).toInt().coerceIn(0, 65535)
        p.yNorm = ((rawY / h) * 65535f).toInt().coerceIn(0, 65535)

        val pressure = try {
            if (historyPos >= 0) e.getHistoricalAxisValue(MotionEvent.AXIS_PRESSURE, idx, historyPos)
            else e.getAxisValue(MotionEvent.AXIS_PRESSURE, idx)
        } catch (_: Exception) {
            e.pressure
        }
        p.pressure = (pressure.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)

        p.barrel = e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        p.eraser = e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER

        if (tiltEnabled) readTilt(p, e, idx, historyPos) else {
            p.tiltPresent = false
            p.tiltX = 0
            p.tiltY = 0
        }
    }

    /**
     * Android exposes a single AXIS_TILT (angle from vertical) plus
     * AXIS_ORIENTATION. We decompose into tiltX/tiltY (centidegrees,
     * -900..900) so the PC's POINTER_PEN_INFO gets sensible 2D values.
     */
    private fun readTilt(p: PenEvent, e: MotionEvent, idx: Int, historyPos: Int) {
        val tilt = try {
            if (historyPos >= 0) e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, historyPos)
            else e.getAxisValue(MotionEvent.AXIS_TILT, idx)
        } catch (_: Exception) { 0f }
        val orient = try {
            if (historyPos >= 0) e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, idx, historyPos)
            else e.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx)
        } catch (_: Exception) { 0f }

        if (tilt <= 0f || tilt.isNaN()) {
            p.tiltPresent = false
            p.tiltX = 0
            p.tiltY = 0
            return
        }
        val deg = tilt * 180f / Math.PI.toFloat()   // 0..90
        val c = (deg * 10f).toInt().coerceIn(0, 900)
        val rad = orient
        p.tiltPresent = true
        p.tiltX = (cos(rad) * c).toInt()
        p.tiltY = (sin(rad) * c).toInt()
    }
}