package org.bayton.gmc.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bayton.gmc.BuildConfig
import org.bayton.gmc.MainActivity
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

data class TokenResult(val accessToken: String?, val refreshToken: String?, val idToken: String?, val expiresIn: Long? = null)

sealed interface RefreshResult {
    data class Success(val tokens: TokenResult) : RefreshResult
    data object Rejected : RefreshResult
    data object Unavailable : RefreshResult
}

/**
 * Native OAuth via the RFC 8252 loopback redirect. Schooble B2C only accepts http://localhost as a
 * redirect for the Xperios public client, so we open the real sign-in in the system browser (no
 * WebView) and catch the auth code on a short-lived local server bound to 127.0.0.1. PKCE, public
 * client, no secret. CSRF-protected by a random, verified `state`.
 */
class LoopbackAuth(
    private val appContext: Context,
    private val http: OkHttpClient = OkHttpClient(),
    private val tokenEndpoint: String = "${BuildConfig.B2C_AUTHORITY.trimEnd('/')}/oauth2/v2.0/token",
) {

    private val authority = BuildConfig.B2C_AUTHORITY.trimEnd('/')
    private val authorizeEndpoint = "$authority/oauth2/v2.0/authorize"
    private val scope = "openid offline_access " +
        "https://SchoobleAD.onmicrosoft.com/Xperios/Users.Read " +
        "https://SchoobleAD.onmicrosoft.com/Xperios/Communications.Read"
    private val timeoutMs = 300_000L

    suspend fun login(openInBrowser: (String) -> Unit): TokenResult? = withContext(Dispatchers.IO) {
        startKeepAlive()
        try {
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(16)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val redirect = "http://localhost:${server.localPort}"
            val url = Uri.parse(authorizeEndpoint).buildUpon()
                .appendQueryParameter("client_id", BuildConfig.B2C_CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_mode", "query")
                .appendQueryParameter("scope", scope)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .build().toString()
            withContext(Dispatchers.Main) { openInBrowser(url) }
            val code = awaitCode(server, state) ?: return@withContext null
            withContext(Dispatchers.Main) { bringAppToFront() }
            exchangeCode(code, verifier, redirect)
        }
        } finally { stopKeepAlive() }
    }

    private fun startKeepAlive() = runCatching {
        val i = android.content.Intent(appContext, LoginKeepAliveService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(appContext, i)
    }
    private fun stopKeepAlive() = runCatching {
        appContext.stopService(android.content.Intent(appContext, LoginKeepAliveService::class.java))
    }

    fun refreshBlocking(refreshToken: String): RefreshResult {
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.B2C_CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", scope)
            .build()
        return try {
            http.newCall(Request.Builder().url(tokenEndpoint).post(body).build()).execute().use { response ->
                val payload = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                when {
                    response.isSuccessful -> payload?.let(::parseTokens)?.takeIf { !it.accessToken.isNullOrBlank() }
                        ?.let { RefreshResult.Success(it) } ?: RefreshResult.Unavailable
                    response.code == 400 && payload?.optString("error") == "invalid_grant" -> RefreshResult.Rejected
                    else -> RefreshResult.Unavailable
                }
            }
        } catch (_: java.io.IOException) { RefreshResult.Unavailable }
    }

    private fun exchangeCode(code: String, verifier: String, redirect: String): TokenResult? = post(
        FormBody.Builder()
            .add("client_id", BuildConfig.B2C_CLIENT_ID)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("code_verifier", verifier)
            .add("scope", scope)
            .build()
    )

    private fun post(body: FormBody): TokenResult? = runCatching {
        http.newCall(Request.Builder().url(tokenEndpoint).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val j = JSONObject(resp.body?.string().orEmpty())
            parseTokens(j)
        }
    }.getOrNull()

    private fun parseTokens(j: JSONObject) = TokenResult(
        accessToken = j.optString("access_token").ifBlank { null },
        refreshToken = j.optString("refresh_token").ifBlank { null },
        idToken = j.optString("id_token").ifBlank { null },
        expiresIn = j.optLong("expires_in", 0).takeIf { it > 0 },
    )

    /**
     * Read the redirect off the loopback socket. Returns the code only when the callback's `state`
     * matches (CSRF guard). Bounded by an overall deadline; each accept/read carries the remaining
     * time so a silent connection cannot hang the flow. Stray requests (e.g. favicon) are skipped.
     */
    private fun awaitCode(server: ServerSocket, expectedState: String): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            server.soTimeout = remaining.toInt().coerceAtLeast(1)
            val socket = try { server.accept() } catch (_: Exception) { return null }
            try {
                socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val query = line.substringAfter("GET ", "").substringBefore(" HTTP").substringAfter('?', "")
                val params = query.split('&').mapNotNull {
                    val kv = it.split('=', limit = 2); if (kv.size == 2) kv[0] to Uri.decode(kv[1]) else null
                }.toMap()
                val code = params["code"]
                val stateOk = constantTimeEquals(params["state"].orEmpty(), expectedState)
                val done = code != null || params.containsKey("error")
                respond(socket, if (code != null && stateOk) "<h2>Signed in.</h2><p>You can close this tab and return to Gwent Music.</p>"
                                else "<h2>Sign-in was not completed.</h2><p>Return to Gwent Music and try again.</p>")
                if (code != null && stateOk) return code   // success
                if (done) return null                        // error, or state mismatch -> reject
                // otherwise a stray request: keep waiting
            } catch (_: Exception) {
                // malformed / slow request: skip and keep waiting until the deadline
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun respond(socket: java.net.Socket, html: String) {
        runCatching {
            socket.getOutputStream().writer().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$html"); flush()
            }
        }
    }

    private fun bringAppToFront() = appContext.startActivity(
        Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    )

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0; for (i in a.indices) r = r or (a[i].code xor b[i].code); return r == 0
    }
    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b); return base64Url(b)
    }
    private fun base64Url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
