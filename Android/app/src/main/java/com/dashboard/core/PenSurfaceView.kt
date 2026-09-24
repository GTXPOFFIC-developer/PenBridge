package com.dashboard.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-level pen/touch capture surface.
 *
 * A plain [View] (rather than Compose pointer APIs) so we can:
 *  - call [requestUnbufferedDispatch] so the OS streams every frame,
 *  - drain [MotionEvent.getHistorical*] batches (no points dropped),
 *  - receive raw hover events via [onGenericMotionEvent],
 *  - render low-latency Pen Trail directly on the canvas.
 *
 * Every sample is normalized to the full tablet surface (0..65535) or active region and
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

    /** Stylus Mode (S-Pen / Active Stylus Only): 100% ignore fingers and palm touches. */
    var stylusOnly: Boolean = false

    /** Raw Input / OSU! Mode: Unfiltered digitization with zero hover throttling for ultra-low latency. */
    var rawInputMode: Boolean = false

    /** Read AXIS_TILT / AXIS_ORIENTATION and forward as tiltX/tiltY. */
    var tiltEnabled: Boolean = true

    /** Mode: Trackpad (relative mouse) vs Tablet (absolute digitizer). */
    var inputMode: InputMode = InputMode.TABLET
    var sensitivity: Float = 1.3f
    var hapticsEnabled: Boolean = true
    var onHaptic: (() -> Unit)? = null

    /** Samsung S-Pen barrel button remapping. */
    var barrelAction: BarrelAction = BarrelAction.RIGHT_CLICK

    /** Pen Trail visual feedback on tablet canvas. */
    var penTrailEnabled: Boolean = true
    var hoverTrailEnabled: Boolean = false
    var trailColor: Int = 0xFF8B5CF6.toInt()

    /** Drawing-Area Selection / Workspace Mapping. */
    var regionActive: Boolean = false
    var regionX0: Int = 0
    var regionY0: Int = 0
    var regionX1: Int = 65535
    var regionY1: Int = 65535

    // Pen Trail rendering state
    private data class TrailPoint(val x: Float, val y: Float, val time: Long, val pressure: Int)
    private val trailPoints = ConcurrentLinkedQueue<TrailPoint>()

    private val trailGlowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trailCorePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trailFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // Hover cursor tracking state
    private var hoverX = -1f
    private var hoverY = -1f
    private var hoverTime = 0L

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

    // Hover timestamp for throttling in standard mode
    private var lastHoverNs = 0L

    init {
        // Ensure onDraw is called for custom pen trail rendering
        setWillNotDraw(false)
    }

    private fun addTrailPoint(x: Float, y: Float, pressure: Int) {
        if (!penTrailEnabled) return
        val now = System.currentTimeMillis()
        trailPoints.add(TrailPoint(x, y, now, pressure))
        invalidate()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!penTrailEnabled) return

        val now = System.currentTimeMillis()
        val trailLifespanMs = 650L
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val baseColor = (if (trailColor != 0) trailColor else 0xFF8B5CF6.toInt()) or 0xFF000000.toInt()

        var hasActive = false

        // 1. Draw hover reticle under stylus tip if pen is hovering above screen
        if (!penDown && hoverX >= 0f && hoverY >= 0f && (now - hoverTime < 300)) {
            val hAge = now - hoverTime
            val hFrac = (1f - hAge.toFloat() / 300f).coerceIn(0f, 1f)
            val hAlpha = (hFrac * 190f).toInt().coerceIn(0, 255)
            val hRadius = 5.5f * density

            trailFillPaint.color = baseColor
            trailFillPaint.alpha = (hAlpha * 0.35f).toInt()
            canvas.drawCircle(hoverX, hoverY, hRadius * 2.2f, trailFillPaint)

            trailCorePaint.color = baseColor
            trailCorePaint.alpha = hAlpha
            trailCorePaint.strokeWidth = 1.75f * density
            canvas.drawCircle(hoverX, hoverY, hRadius, trailCorePaint)
            hasActive = true
        }

        // 2. Render pen trail strokes and points
        if (trailPoints.isNotEmpty()) {
            // Prune trail points older than trailLifespanMs
            while (trailPoints.peek()?.let { now - it.time > trailLifespanMs } == true) {
                trailPoints.poll()
            }

            val points = trailPoints.toList()
            if (points.isNotEmpty()) {
                if (points.size == 1) {
                    val p = points[0]
                    val age = now - p.time
                    if (age <= trailLifespanMs) {
                        hasActive = true
                        val frac = (1f - age.toFloat() / trailLifespanMs).coerceIn(0f, 1f)
                        val alpha = (frac * 240f).toInt().coerceIn(0, 255)
                        val radius = (3.5f + (p.pressure / 65535f) * 6.5f) * density

                        // Outer glow
                        trailFillPaint.color = baseColor
                        trailFillPaint.alpha = (alpha * 0.38f).toInt()
                        canvas.drawCircle(p.x, p.y, radius * 2.2f, trailFillPaint)

                        // Core dot
                        trailFillPaint.alpha = alpha
                        canvas.drawCircle(p.x, p.y, radius, trailFillPaint)
                    }
                } else {
                    // Draw dual-layer continuous stroke (outer neon glow + high-vibrancy core)
                    for (i in 1 until points.size) {
                        val p0 = points[i - 1]
                        val p1 = points[i]
                        val age = now - p1.time
                        if (age > trailLifespanMs) continue
                        hasActive = true
                        val frac = (1f - age.toFloat() / trailLifespanMs).coerceIn(0f, 1f)
                        val alpha = (frac * 240f).toInt().coerceIn(0, 255)
                        val strokeW = (3.5f + (p1.pressure / 65535f) * 8.5f) * density

                        // Glow layer
                        trailGlowPaint.color = baseColor
                        trailGlowPaint.alpha = (alpha * 0.35f).toInt()
                        trailGlowPaint.strokeWidth = strokeW * 2.2f
                        canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailGlowPaint)

                        // Core layer
                        trailCorePaint.color = baseColor
                        trailCorePaint.alpha = alpha
                        trailCorePaint.strokeWidth = strokeW
                        canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailCorePaint)
                    }

                    // Draw crisp cursor nib at the leading point
                    val latest = points.last()
                    val latestAge = now - latest.time
                    if (latestAge <= trailLifespanMs) {
                        val frac = (1f - latestAge.toFloat() / trailLifespanMs).coerceIn(0f, 1f)
                        val alpha = (frac * 255f).toInt().coerceIn(0, 255)
                        val nibRadius = (4f + (latest.pressure / 65535f) * 6f) * density

                        trailFillPaint.color = baseColor
                        trailFillPaint.alpha = (alpha * 0.4f).toInt()
                        canvas.drawCircle(latest.x, latest.y, nibRadius * 2.0f, trailFillPaint)

                        trailFillPaint.color = 0xFFFFFFFF.toInt()
                        trailFillPaint.alpha = alpha
                        canvas.drawCircle(latest.x, latest.y, nibRadius * 0.65f, trailFillPaint)
                    }
                }
            }
        }

        if (hasActive) {
            postInvalidateOnAnimation()
        }
    }

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
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            hoverX = -1f
            hoverY = -1f
            invalidate()
            return true
        }
        if (action == MotionEvent.ACTION_HOVER_MOVE ||
            action == MotionEvent.ACTION_HOVER_ENTER
        ) {
            val idx = event.actionIndex.coerceAtLeast(0)
            if (!toolAccepted(event.getToolType(idx))) return true

            // In Raw Input / OSU! Mode, bypass hover throttling completely
            if (!rawInputMode) {
                val now = event.eventTime
                if (now - lastHoverNs < 8_000_000L && !hoversChanged(event)) return true
                lastHoverNs = now
            }

            val px = if (idx < event.pointerCount) event.getX(idx) else event.x
            val py = if (idx < event.pointerCount) event.getY(idx) else event.y

            val p = PenEvent()
            p.action = Const.ACTION_HOVER
            p.contact = false
            fill(p, event, idx, px, py)

            hoverX = px
            hoverY = py
            hoverTime = System.currentTimeMillis()

            if (penTrailEnabled && hoverTrailEnabled) {
                addTrailPoint(px, py, 0)
            } else if (penTrailEnabled) {
                invalidate()
            }

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
                            val p = PenEvent()
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
                    val p = PenEvent()
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
                if (!pointerMoved && elapsed < 350) {
                    // Tap = Left Click
                    val down = PenEvent()
                    down.action = Const.ACTION_DOWN
                    down.relative = true
                    down.contact = true
                    down.xNorm = 0
                    down.yNorm = 0
                    onSample(down)

                    val up = PenEvent()
                    up.action = Const.ACTION_UP
                    up.relative = true
                    up.contact = false
                    up.xNorm = 0
                    up.yNorm = 0
                    onSample(up)

                    if (hapticsEnabled) onHaptic?.invoke()
                }

                lastTouchX = -1f
                lastTouchY = -1f
                subpixelX = 0f
                subpixelY = 0f
                twoFingerScroll = false
                pointerMoved = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFingerScroll) {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!pointerMoved && elapsed < 350) {
                        // Two-finger tap = Right Click
                        val down = PenEvent()
                        down.action = Const.ACTION_DOWN
                        down.barrel = true
                        down.relative = true
                        down.xNorm = 0
                        down.yNorm = 0
                        onSample(down)

                        val up = PenEvent()
                        up.action = Const.ACTION_UP
                        up.barrel = true
                        up.relative = true
                        up.xNorm = 0
                        up.yNorm = 0
                        onSample(up)

                        if (hapticsEnabled) onHaptic?.invoke()
                    }
                    twoFingerScroll = false
                    lastTwoFingerY = -1f
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    if (event.pointerCount > remaining) {
                        lastTouchX = event.getX(remaining)
                        lastTouchY = event.getY(remaining)
                    } else {
                        lastTouchX = -1f
                        lastTouchY = -1f
                    }
                    subpixelX = 0f
                    subpixelY = 0f
                    pointerMoved = true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTouchX = -1f
                lastTouchY = -1f
                subpixelX = 0f
                subpixelY = 0f
                twoFingerScroll = false
                pointerMoved = false
            }
        }
    }

    private fun process(event: MotionEvent, fromHistory: Boolean) {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val idx = event.actionIndex
                if (!toolAccepted(event.getToolType(idx))) return
                hoverX = -1f
                hoverY = -1f
                activePointerId = event.getPointerId(idx)
                penDown = true
                if (!contactSent) { onContactChanged(true); contactSent = true }
                if (hapticsEnabled) onHaptic?.invoke()

                val px = event.getX(idx)
                val py = event.getY(idx)

                val p = PenEvent()
                p.action = Const.ACTION_DOWN
                p.contact = true
                fill(p, event, idx, px, py)
                addTrailPoint(px, py, p.pressure)
                onSample(p)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val tool = event.getToolType(idx)
                // If active stylus touches while palm or fingers are present, prioritize stylus
                if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                    hoverX = -1f
                    hoverY = -1f
                    activePointerId = event.getPointerId(idx)
                    penDown = true
                    if (!contactSent) { onContactChanged(true); contactSent = true }
                    if (hapticsEnabled) onHaptic?.invoke()

                    val px = event.getX(idx)
                    val py = event.getY(idx)

                    val p = PenEvent()
                    p.action = Const.ACTION_DOWN
                    p.contact = true
                    fill(p, event, idx, px, py)
                    addTrailPoint(px, py, p.pressure)
                    onSample(p)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = indexOfPointer(event, activePointerId).let { if (it >= 0) it else event.actionIndex.coerceAtLeast(0) }
                if (idx >= event.pointerCount) return
                val tool = event.getToolType(idx)
                if (!toolAccepted(tool)) return

                if (!penDown) {
                    penDown = true
                    activePointerId = event.getPointerId(idx)
                    hoverX = -1f
                    hoverY = -1f
                    if (!contactSent) { onContactChanged(true); contactSent = true }
                    if (hapticsEnabled) onHaptic?.invoke()

                    val pDown = PenEvent()
                    pDown.action = Const.ACTION_DOWN
                    pDown.contact = true
                    val pxDown = event.getX(idx)
                    val pyDown = event.getY(idx)
                    fill(pDown, event, idx, pxDown, pyDown)
                    addTrailPoint(pxDown, pyDown, pDown.pressure)
                    onSample(pDown)
                }

                // Drain historical frames first (exact coordinates from hardware queue)
                val historyCount = event.historySize
                for (h in 0 until historyCount) {
                    val hx = event.getHistoricalX(idx, h)
                    val hy = event.getHistoricalY(idx, h)
                    val p = PenEvent()
                    p.action = Const.ACTION_MOVE
                    p.contact = true
                    fill(p, event, idx, hx, hy, h)
                    addTrailPoint(hx, hy, p.pressure)
                    onSample(p)
                }

                // Current frame using precise pointer index
                val px = event.getX(idx)
                val py = event.getY(idx)
                val p = PenEvent()
                p.action = Const.ACTION_MOVE
                p.contact = true
                fill(p, event, idx, px, py)
                addTrailPoint(px, py, p.pressure)
                onSample(p)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId) {
                    val px = event.getX(idx)
                    val py = event.getY(idx)
                    val p = PenEvent()
                    p.action = Const.ACTION_UP
                    p.contact = false
                    fill(p, event, idx, px, py)
                    onSample(p)
                    penDown = false
                    activePointerId = -1
                    if (contactSent) { onContactChanged(false); contactSent = false }
                }
            }
            MotionEvent.ACTION_UP -> {
                val idx = indexOfPointer(event, activePointerId).let { if (it >= 0) it else event.actionIndex.coerceAtLeast(0) }
                val px = if (idx < event.pointerCount) event.getX(idx) else event.x
                val py = if (idx < event.pointerCount) event.getY(idx) else event.y
                val p = PenEvent()
                p.action = Const.ACTION_UP
                p.contact = false
                fill(p, event, idx, px, py)
                onSample(p)
                penDown = false
                activePointerId = -1
                if (contactSent) { onContactChanged(false); contactSent = false }
            }
            MotionEvent.ACTION_CANCEL -> {
                val idx = indexOfPointer(event, activePointerId).let { if (it >= 0) it else event.actionIndex.coerceAtLeast(0) }
                val px = if (idx < event.pointerCount) event.getX(idx) else event.x
                val py = if (idx < event.pointerCount) event.getY(idx) else event.y
                val p = PenEvent()
                p.action = Const.ACTION_UP
                p.contact = false
                fill(p, event, idx, px, py)
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
            else -> if (stylusOnly) false else (fingerEnabled && !(palmRejection && penDown))
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
        p.relative = false
        val w = if (width > 0) width.toFloat() else 1f
        val h = if (height > 0) height.toFloat() else 1f

        val normX = (rawX / w).coerceIn(0f, 1f)
        val normY = (rawY / h).coerceIn(0f, 1f)

        if (regionActive && (regionX0 > 0 || regionY0 > 0 || regionX1 < 65535 || regionY1 < 65535)) {
            val rx0 = regionX0 / 65535f
            val ry0 = regionY0 / 65535f
            val rx1 = regionX1 / 65535f
            val ry1 = regionY1 / 65535f
            val rw = (rx1 - rx0).coerceAtLeast(0.01f)
            val rh = (ry1 - ry0).coerceAtLeast(0.01f)
            val mappedX = ((normX - rx0) / rw).coerceIn(0f, 1f)
            val mappedY = ((normY - ry0) / rh).coerceIn(0f, 1f)
            p.xNorm = (mappedX * 65535f).toInt().coerceIn(0, 65535)
            p.yNorm = (mappedY * 65535f).toInt().coerceIn(0, 65535)
        } else {
            p.xNorm = (normX * 65535f).toInt().coerceIn(0, 65535)
            p.yNorm = (normY * 65535f).toInt().coerceIn(0, 65535)
        }

        val rawPressure = try {
            if (historyPos >= 0) e.getHistoricalAxisValue(MotionEvent.AXIS_PRESSURE, idx, historyPos)
            else e.getAxisValue(MotionEvent.AXIS_PRESSURE, idx)
        } catch (_: Exception) {
            e.pressure
        }
        var computedPressure = (rawPressure.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)
        if (p.contact && computedPressure <= 0) {
            computedPressure = 32768 // Default to 50% pressure for capacitive styluses / fingers
        }
        p.pressure = computedPressure

        // Samsung S-Pen barrel button & hardware eraser detection
        val isHardwareEraser = (idx < e.pointerCount && e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER)
        val isBarrelPressed = e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
                              e.isButtonPressed(MotionEvent.BUTTON_SECONDARY) ||
                              e.isButtonPressed(MotionEvent.BUTTON_TERTIARY)

        when {
            isHardwareEraser -> {
                p.eraser = true
            }
            isBarrelPressed -> {
                when (barrelAction) {
                    BarrelAction.RIGHT_CLICK -> p.barrel = true
                    BarrelAction.ERASER -> p.eraser = true
                    BarrelAction.MIDDLE_CLICK -> p.middle = true
                    BarrelAction.DOUBLE_CLICK -> p.doubleClick = true
                    BarrelAction.UNDO -> p.undo = true
                }
            }
        }

        if (tiltEnabled) readTilt(p, e, idx, historyPos) else {
            p.tiltPresent = false
            p.tiltX = 0
            p.tiltY = 0
        }
    }

    /**
     * Android exposes AXIS_TILT plus AXIS_ORIENTATION.
     * Decomposes into tiltX/tiltY (centidegrees, -900..900).
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