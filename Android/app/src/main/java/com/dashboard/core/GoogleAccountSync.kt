package com.dashboard.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * Optional "Sign in with Google" account link — Chrome Remote Desktop style
 * pairing without any backend.
 *
 * How it works (no Firestore, no relay, no cost):
 *  1. The tablet signs in with Google (OAuth2 PKCE, browser + loopback).
 *  2. During the connection handshake the tablet sends its Google access
 *     token + email to the PC *over the existing USB/Wi-Fi link*.
 *  3. The PC verifies the token through Google's free `tokeninfo` endpoint.
 *     Same Google account as the PC  -> auto-authorized.
 *     Different / no account        -> 6-digit pairing as before.
 *
 * Until [GoogleConfig.clientId] is filled in, everything is inert and the
 * UI shows a friendly "configure" hint.
 */
class GoogleAccountSync(context: Context) {

    private val prefs = context.getSharedPreferences("dashboard_google", Context.MODE_PRIVATE)

    val available: Boolean get() = GoogleConfig.clientId.isNotBlank()

    val signedIn: Boolean get() = accessToken().isNotBlank() && refreshToken().isNotBlank()
    val email: String? get() = prefs.getString("email", null) ?: prefs.getString("email_cache", null)
    val displayName: String? get() = prefs.getString("name", null)
    val pictureUrl: String? get() = prefs.getString("picture", null)

    fun accessToken() = prefs.getString("access_token", "") ?: ""
    private fun refreshToken() = prefs.getString("refresh_token", "") ?: ""
    fun clear() = prefs.edit().clear().apply()

    private var stepLock = false

    /**
     * Full sign-in: browser consent -> loopback callback -> token exchange
     * -> profile load. Returns the account email on success.
     */
    suspend fun signIn(openBrowser: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        if (stepLock) return@withContext null
        stepLock = true
        try {
            val verifier = randomB64(48)
            val s256 = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val state = randomB64(16)
            val port = 28314
            val url = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=${enc(GoogleConfig.clientId)}" +
                "&redirect_uri=${enc("http://localhost:$port/")}" +
                "&response_type=code&scope=${enc("openid email profile")}" +
                "&code_challenge=$s256&code_challenge_method=S256&state=$state&prompt=select_account"

            val server = ServerSocket(port, 2, java.net.InetAddress.getByName("127.0.0.1"))
            openBrowser(url)
            val code = acceptCode(server, state) ?: return@withContext null

            val tok = postForm("https://oauth2.googleapis.com/token",
                "code=${enc(code)}&client_id=${enc(GoogleConfig.clientId)}" +
                    "&client_secret=${enc(GoogleConfig.clientSecret)}".takeIf { GoogleConfig.clientSecret.isNotBlank() }.orEmpty() +
                    "&redirect_uri=${enc("http://localhost:$port/")}" +
                    "&grant_type=authorization_code&code_verifier=${enc(verifier)}")
            val idToken = tok.optString("id_token")
            if (idToken.isBlank()) return@withContext null

            val prof = getJson("https://oauth2.googleapis.com/tokeninfo?id_token=${enc(idToken)}") ?: return@withContext null
            val email = prof.optString("email")
            if (email.isBlank()) return@withContext null
            prefs.edit()
                .putString("access_token", tok.optString("access_token"))
                .putString("refresh_token", tok.optString("refresh_token"))
                .putString("email", email)
                .putString("name", prof.optString("name").ifBlank { email.substringBefore('@') })
                .putString("picture", prof.optString("picture"))
                .putLong("expires_at", System.currentTimeMillis() + 3600000)
                .putBoolean("known_email", true)
                .apply()
            email
        } finally {
            stepLock = false
        }
    }

    /** Pulls a valid token, refreshing when needed. Returns null on failure. */
    suspend fun ensureAccessToken(): String? = withContext(Dispatchers.IO) {
        val at = accessToken()
        val exp = prefs.getLong("expires_at", 0)
        if (at.isNotBlank() && System.currentTimeMillis() < exp - 60000) return@withContext at
        val rt = prefs.getString("refresh_token", "") ?: ""
        if (rt.isBlank()) return@withContext null
        val tok = postForm("https://oauth2.googleapis.com/token",
            "client_id=${enc(GoogleConfig.clientId)}" +
                "&client_secret=${enc(GoogleConfig.clientSecret)}".takeIf { GoogleConfig.clientSecret.isNotBlank() }.orEmpty() +
                "&grant_type=refresh_token&refresh_token=${enc(rt)}")
        val nt = tok.optString("access_token")
        if (nt.isBlank()) return@withContext null
        prefs.edit()
            .putString("access_token", nt)
            .putLong("expires_at", System.currentTimeMillis() + 3600000)
            .apply()
        nt
    }

    // ------------------------------------------------------------ IO

    private fun acceptCode(server: ServerSocket, state: String): String? {
        val client = try { server.accept() } catch (_: Exception) { return null }
        try {
            val rdr = BufferedReader(InputStreamReader(client.getInputStream()))
            val request = rdr.readLine() ?: return null
            val path = request.split(" ").getOrNull(1) ?: "/"
            val params = path.substringAfter("?").split("&").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else null
            }.toMap()
            val body = "<!doctype html><html><head><meta name='viewport' content='width=device-width'></head>" +
                "<body style='background:#0B0E1A;color:#ECEEFF;font-family:Segoe UI,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>" +
                "<div style='text-align:center'><h1 style='margin:0 0 8px'>PenBridge</h1><p>Connected to your Google account.<br>You can close this tab.</p></div></body></html>"
            val bytes = body.toByteArray()
            val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().write(resp.toByteArray())
            client.getOutputStream().write(bytes)
            client.getOutputStream().flush()
            if (params["state"] != state) return null
            return params["code"]
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}
        }
    }

    private fun postForm(url: String, body: String): JSONObject {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (_: Exception) {
            return JSONObject()
        }
    }

    private fun getJson(url: String): JSONObject? {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 10000
            c.readTimeout = 10000
            if (c.responseCode !in 200..299) return null
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            return null
        }
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun encPath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun randomB64(bytes: Int): String {
            val r = ByteArray(bytes); SecureRandom().nextBytes(r)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(r)
        }
        private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

/**
 * Fill this in to enable Chrome-Remote-Desktop-style auto-pairing across
 * your own Google account. README explains creating the OAuth client.
 * Only needs a Google Cloud **Web** OAuth client — no billing, no database.
 */
object GoogleConfig {
    /** From Google Cloud Console → APIs & Services → Credentials → OAuth client. */
    const val clientId = "YOUR_GOOGLE_CLIENT_ID_HERE"

    /**
     * Client secret. Only required for OAuth clients of type "Web
     * application". If you create a *Desktop* client instead, remove this
     * value (PKCE alone is sufficient and much safer in a distributed app).
     */
    const val clientSecret = "YOUR_GOOGLE_CLIENT_SECRET_HERE"
}