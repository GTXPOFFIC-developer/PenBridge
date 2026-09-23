package com.dashboard.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SavedSession(
    val id: String,                 // unique session ID (e.g. hostIp or deviceId)
    val name: String,               // Host display name (e.g. "Workstation-PC")
    val ip: String,                 // IP address or "usb"
    val port: Int = Const.DATA_PORT,
    val mode: ConnectMode = ConnectMode.WIFI,
    val lastConnected: Long = System.currentTimeMillis(),
    val isPaired: Boolean = true,
    val authMethod: String = "PIN", // "PIN", "Password", "Google"
)

/**
 * Persistent session storage that remembers previous connections across app launches.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("dashboard_sessions", Context.MODE_PRIVATE)

    private val _sessions = MutableStateFlow<List<SavedSession>>(load())
    val sessions: StateFlow<List<SavedSession>> = _sessions

    private fun load(): List<SavedSession> {
        val jsonStr = prefs.getString("saved_sessions_list", null) ?: return emptyList()
        val list = mutableListOf<SavedSession>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ip = obj.optString("ip", "")
                if (ip.isBlank()) continue
                list.add(
                    SavedSession(
                        id = obj.optString("id", ip),
                        name = obj.optString("name", "Host PC"),
                        ip = ip,
                        port = obj.optInt("port", Const.DATA_PORT),
                        mode = if (obj.optString("mode") == "USB") ConnectMode.USB else ConnectMode.WIFI,
                        lastConnected = obj.optLong("lastConnected", 0L),
                        isPaired = obj.optBoolean("isPaired", true),
                        authMethod = obj.optString("authMethod", "PIN"),
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.lastConnected }
    }

    @Synchronized
    private fun persist(list: List<SavedSession>) {
        _sessions.value = list.sortedByDescending { it.lastConnected }
        val arr = JSONArray()
        for (s in _sessions.value) {
            val obj = JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("ip", s.ip)
                put("port", s.port)
                put("mode", s.mode.name)
                put("lastConnected", s.lastConnected)
                put("isPaired", s.isPaired)
                put("authMethod", s.authMethod)
            }
            arr.put(obj)
        }
        prefs.edit().putString("saved_sessions_list", arr.toString()).apply()
    }

    fun upsertSession(session: SavedSession) {
        val current = _sessions.value.toMutableList()
        val idx = current.indexOfFirst { it.id == session.id || (it.ip.isNotBlank() && it.ip == session.ip) }
        if (idx >= 0) {
            current[idx] = session
        } else {
            current.add(0, session)
        }
        persist(current)
    }

    fun removeSession(id: String) {
        val current = _sessions.value.filter { it.id != id }
        persist(current)
    }

    fun markRevoked(ipOrId: String) {
        val current = _sessions.value.map {
            if (it.id == ipOrId || it.ip == ipOrId) it.copy(isPaired = false) else it
        }
        persist(current)
    }

    fun clearAll() {
        persist(emptyList())
    }
}
