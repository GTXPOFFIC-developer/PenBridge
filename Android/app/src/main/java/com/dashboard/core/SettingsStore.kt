package com.dashboard.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A normalized pressure response point: input from the stylus (raw 0..65535)
 * mapped to output applied by the Windows host.
 */
data class CurvePoint(val input: Int, val output: Int)

enum class ConnectMode { USB, WIFI }

enum class MappingMode { FULL, CUSTOM }

enum class InputMode { TRACKPAD, TABLET }

enum class BarrelAction { RIGHT_CLICK, MIDDLE_CLICK, ERASER, DOUBLE_CLICK, UNDO }

enum class AreaPreset { FULL, SCREEN_16_9, SCREEN_16_10, TOP_HALF, BOTTOM_HALF, CUSTOM }

data class AppSettings(
    val mode: ConnectMode = ConnectMode.WIFI,
    val hostIp: String = "",
    val lastHostName: String = "",
    val inputMode: InputMode = InputMode.TABLET,
    val trackpadSensitivity: Float = 1.3f,
    val fingerInput: Boolean = true,
    val palmRejection: Boolean = true,
    val tiltEnabled: Boolean = true,
    val haptics: Boolean = true,
    val accentIndex: Int = 0,
    val mappingMode: MappingMode = MappingMode.FULL,
    val regionX0: Int = 0, val regionY0: Int = 0,
    val regionX1: Int = 65535, val regionY1: Int = 65535,
    val aspectLock: Boolean = true,
    val rotationDeg: Int = 0,
    val curve: List<CurvePoint> = DEFAULT_CURVE,
    val barrelAction: BarrelAction = BarrelAction.RIGHT_CLICK,
    val penTrail: Boolean = true,
    val hoverTrail: Boolean = false,
    val stylusOnly: Boolean = false,
    val rawInputMode: Boolean = false,
    val drawingAreaPreset: AreaPreset = AreaPreset.FULL,
) {
    companion object {
        val DEFAULT_CURVE = listOf(
            CurvePoint(0, 0),
            CurvePoint(32768, 32768),
            CurvePoint(65535, 65535),
        )
    }
}

/**
 * Tiny persisted key/value store on top of SharedPreferences. All writes are
 * synchronous (rare, tiny), and [AppSettings] is exposed as a [StateFlow].
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("dashboard_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private fun load(): AppSettings {
        val curve = parseCurve(prefs.getString(KEY_CURVE, null))
        return AppSettings(
            mode = if (prefs.getString(KEY_MODE, "WIFI") == "USB") ConnectMode.USB else ConnectMode.WIFI,
            hostIp = prefs.getString(KEY_HOST_IP, "") ?: "",
            lastHostName = prefs.getString(KEY_LAST_HOST_NAME, "") ?: "",
            inputMode = if (prefs.getString(KEY_INPUT_MODE, "TABLET") == "TRACKPAD") InputMode.TRACKPAD else InputMode.TABLET,
            trackpadSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.3f),
            fingerInput = prefs.getBoolean(KEY_FINGER, true),
            palmRejection = prefs.getBoolean(KEY_PALM, true),
            tiltEnabled = prefs.getBoolean(KEY_TILT, true),
            haptics = prefs.getBoolean(KEY_HAPTICS, true),
            accentIndex = prefs.getInt(KEY_ACCENT, 0),
            mappingMode = if (prefs.getString(KEY_MAP_MODE, "FULL") == "CUSTOM") MappingMode.CUSTOM else MappingMode.FULL,
            regionX0 = prefs.getInt(KEY_RX0, 0), regionY0 = prefs.getInt(KEY_RY0, 0),
            regionX1 = prefs.getInt(KEY_RX1, 65535), regionY1 = prefs.getInt(KEY_RY1, 65535),
            aspectLock = prefs.getBoolean(KEY_ASPECT, true),
            rotationDeg = prefs.getInt(KEY_ROTATION, 0),
            curve = curve,
            barrelAction = try { BarrelAction.valueOf(prefs.getString(KEY_BARREL_ACTION, "RIGHT_CLICK") ?: "RIGHT_CLICK") } catch (_: Exception) { BarrelAction.RIGHT_CLICK },
            penTrail = prefs.getBoolean(KEY_PEN_TRAIL, true),
            hoverTrail = prefs.getBoolean(KEY_HOVER_TRAIL, false),
            stylusOnly = prefs.getBoolean(KEY_STYLUS_ONLY, false),
            rawInputMode = prefs.getBoolean(KEY_RAW_INPUT, false),
            drawingAreaPreset = try { AreaPreset.valueOf(prefs.getString(KEY_AREA_PRESET, "FULL") ?: "FULL") } catch (_: Exception) { AreaPreset.FULL },
        )
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        _settings.value = next
        val e = prefs.edit()
        e.putString(KEY_MODE, next.mode.name)
        e.putString(KEY_HOST_IP, next.hostIp)
        e.putString(KEY_LAST_HOST_NAME, next.lastHostName)
        e.putString(KEY_INPUT_MODE, next.inputMode.name)
        e.putFloat(KEY_SENSITIVITY, next.trackpadSensitivity)
        e.putBoolean(KEY_FINGER, next.fingerInput)
        e.putBoolean(KEY_PALM, next.palmRejection)
        e.putBoolean(KEY_TILT, next.tiltEnabled)
        e.putBoolean(KEY_HAPTICS, next.haptics)
        e.putInt(KEY_ACCENT, next.accentIndex)
        e.putString(KEY_MAP_MODE, next.mappingMode.name)
        e.putInt(KEY_RX0, next.regionX0); e.putInt(KEY_RY0, next.regionY0)
        e.putInt(KEY_RX1, next.regionX1); e.putInt(KEY_RY1, next.regionY1)
        e.putBoolean(KEY_ASPECT, next.aspectLock)
        e.putInt(KEY_ROTATION, next.rotationDeg)
        e.putString(KEY_CURVE, encodeCurve(next.curve))
        e.putString(KEY_BARREL_ACTION, next.barrelAction.name)
        e.putBoolean(KEY_PEN_TRAIL, next.penTrail)
        e.putBoolean(KEY_HOVER_TRAIL, next.hoverTrail)
        e.putBoolean(KEY_STYLUS_ONLY, next.stylusOnly)
        e.putBoolean(KEY_RAW_INPUT, next.rawInputMode)
        e.putString(KEY_AREA_PRESET, next.drawingAreaPreset.name)
        e.apply()
        return next
    }

    private fun encodeCurve(c: List<CurvePoint>): String =
        c.take(16).joinToString(",") { "${it.input},${it.output}" }

    private fun parseCurve(s: String?): List<CurvePoint> {
        if (s.isNullOrEmpty()) return AppSettings.DEFAULT_CURVE
        return try {
            val v = s.split(",").map { it.toInt() }
            v.chunked(2).take(16).map { CurvePoint(it[0], it[1]) }.sortedBy { it.input }
                .let { list -> if (list.isEmpty()) AppSettings.DEFAULT_CURVE else list }
        } catch (_: Exception) {
            AppSettings.DEFAULT_CURVE
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_HOST_IP = "host_ip"
        const val KEY_LAST_HOST_NAME = "last_host_name"
        const val KEY_INPUT_MODE = "input_mode"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_FINGER = "finger"
        const val KEY_PALM = "palm"
        const val KEY_TILT = "tilt"
        const val KEY_HAPTICS = "haptics"
        const val KEY_ACCENT = "accent"
        const val KEY_MAP_MODE = "map_mode"
        const val KEY_RX0 = "rx0"; const val KEY_RY0 = "ry0"
        const val KEY_RX1 = "rx1"; const val KEY_RY1 = "ry1"
        const val KEY_ASPECT = "aspect"
        const val KEY_ROTATION = "rotation"
        const val KEY_CURVE = "curve"
        const val KEY_BARREL_ACTION = "barrel_action"
        const val KEY_PEN_TRAIL = "pen_trail"
        const val KEY_HOVER_TRAIL = "hover_trail"
        const val KEY_STYLUS_ONLY = "stylus_only"
        const val KEY_RAW_INPUT = "raw_input"
        const val KEY_AREA_PRESET = "area_preset"
    }
}