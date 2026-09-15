package org.bayton.gmc.data.auth

import android.util.Base64
import org.json.JSONObject
import java.io.IOException

class ReauthenticationRequired : IOException("Your session has expired. Please sign in again.")
class AuthenticationUnavailable : IOException("Sign-in service is temporarily unavailable. Please try again.")

/** Serialises refreshes, but never holds the credential lock while doing network I/O. */
class BearerGate(
    private val tokens: TokenStore,
    private val refresh: (String) -> RefreshResult,
    private val invalidate: () -> Unit,
    private val allowMock: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Any()

    fun credentials(): Credentials {
        val current = tokens.snapshot()
        if (current.access.isNullOrBlank()) reject(current)
        return if (usable(current)) current else renew(current)
    }

    fun rejected(failed: Credentials): Credentials {
        // Persist rejection so a transient refresh outage cannot make the next call reuse it.
        val current = tokens.expire(failed)
        return renew(if (current.access == failed.access && current.generation == failed.generation) current else failed)
    }

    fun isCurrent(credentials: Credentials): Boolean = tokens.snapshot().let {
        it.generation == credentials.generation && !it.access.isNullOrBlank()
    }

    private fun usable(value: Credentials): Boolean {
        if (value.expiresAt == 1L) return false // server has already rejected this token
        if (allowMock && value.access == "mock-access-token") return true
        // JWT exp is only a local freshness hint, never signature/identity validation.
        val expiry = value.expiresAt.takeIf { it > 0 } ?: runCatching {
            val payload = value.access!!.split('.')[1]
            JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)))
                .getLong("exp").takeIf { it in 1..(Long.MAX_VALUE / 1000) }!! * 1000
        }.getOrDefault(0)
        return expiry > now() + 30_000
    }

    private fun renew(expected: Credentials): Credentials = synchronized(refreshLock) {
        val current = tokens.snapshot()
        if (current.generation != expected.generation) throw ReauthenticationRequired()
        if (current != expected && usable(current)) return@synchronized current
        val refreshToken = current.refresh?.takeIf { it.isNotBlank() } ?: reject(current)
        when (val result = refresh(refreshToken)) {
            is RefreshResult.Success -> {
                val replacement = tokens.replace(current, result.tokens, now()) ?: throw ReauthenticationRequired()
                if (!isCurrent(replacement)) throw ReauthenticationRequired()
                replacement.also { if (!usable(it)) throw AuthenticationUnavailable() }
            }
            RefreshResult.Rejected -> reject(current)
            RefreshResult.Unavailable -> throw AuthenticationUnavailable()
        }
    }

    fun reject(current: Credentials): Nothing {
        tokens.invalidate(current, invalidate)
        throw ReauthenticationRequired()
    }
}
