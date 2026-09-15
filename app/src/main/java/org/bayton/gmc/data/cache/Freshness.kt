package org.bayton.gmc.data.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bayton.gmc.data.auth.AuthenticationUnavailable
import org.bayton.gmc.data.auth.ReauthenticationRequired
import org.bayton.gmc.data.model.ServiceResponseException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How current the data a screen is showing is, and why a refresh ended as it did (FR-D02). Screens
 * show their cached snapshot with one of these while a refresh runs or after it fails, so the state
 * of the data is visible rather than hidden behind a spinner.
 */
enum class Freshness {
    SYNCING,          // a refresh is in flight (cache shown underneath, if any)
    CURRENT,          // showing a result just fetched successfully
    STALE,            // showing cache; last refresh failed for a non-specific reason
    OFFLINE,          // showing cache; the device has no working connection
    SERVICE_HOLD,     // showing cache; the device is online but Gwent Music isn't responding
    REAUTH_REQUIRED,  // the session has expired; reconnect required
}

/** Minimal connectivity probe, so OFFLINE (our connection) is distinguished from SERVICE_HOLD (theirs). */
@Singleton
class Connectivity @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true // assume online if unknowable
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Maps a refresh failure to the freshness a screen should show over its (kept) cached snapshot. */
object FreshnessClassifier {
    fun classify(error: Throwable, online: Boolean): Freshness = when {
        error is ReauthenticationRequired -> Freshness.REAUTH_REQUIRED
        !online -> Freshness.OFFLINE
        // Online, but Gwent Music itself is the problem: the endpoint accepted the connection and never
        // answered (today's Diary hang), replied 200 with an unusable/aborted body, failed the sign-in
        // refresh, or returned a server error. All "them", not us.
        error is ServiceResponseException -> Freshness.SERVICE_HOLD
        error is SocketTimeoutException -> Freshness.SERVICE_HOLD
        error is AuthenticationUnavailable -> Freshness.SERVICE_HOLD
        error is HttpException && error.code() >= 500 -> Freshness.SERVICE_HOLD
        // The host is genuinely unreachable despite the connectivity flag: treat as offline.
        error is UnknownHostException || error is ConnectException -> Freshness.OFFLINE
        // Any other failure while online is not evidence the device is offline.
        else -> Freshness.STALE
    }
}
