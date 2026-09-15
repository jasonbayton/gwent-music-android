package org.bayton.gmc.data.cache

import okhttp3.ResponseBody.Companion.toResponseBody
import org.bayton.gmc.data.auth.AuthenticationUnavailable
import org.bayton.gmc.data.auth.ReauthenticationRequired
import org.bayton.gmc.data.model.ServiceResponseException
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FreshnessClassifierTest {
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    @Test fun `expired session is reauth regardless of connectivity`() {
        assertEquals(Freshness.REAUTH_REQUIRED, FreshnessClassifier.classify(ReauthenticationRequired(), online = true))
        assertEquals(Freshness.REAUTH_REQUIRED, FreshnessClassifier.classify(ReauthenticationRequired(), online = false))
    }

    @Test fun `no connectivity is offline for any non-reauth error`() {
        assertEquals(Freshness.OFFLINE, FreshnessClassifier.classify(SocketTimeoutException(), online = false))
        assertEquals(Freshness.OFFLINE, FreshnessClassifier.classify(IllegalStateException("x"), online = false))
    }

    @Test fun `online timeout is a service hold, not offline`() {
        // The exact incident: connected, but the endpoint never answered.
        assertEquals(Freshness.SERVICE_HOLD, FreshnessClassifier.classify(SocketTimeoutException(), online = true))
    }

    @Test fun `online server error, sign-in outage and unusable response are service holds`() {
        assertEquals(Freshness.SERVICE_HOLD, FreshnessClassifier.classify(http(503), online = true))
        assertEquals(Freshness.SERVICE_HOLD, FreshnessClassifier.classify(http(500), online = true))
        assertEquals(Freshness.SERVICE_HOLD, FreshnessClassifier.classify(AuthenticationUnavailable(), online = true))
        // A 200 with an unusable/aborted body is the service holding, not the device being offline.
        assertEquals(Freshness.SERVICE_HOLD, FreshnessClassifier.classify(ServiceResponseException("incomplete"), online = true))
    }

    @Test fun `online client error is stale, not a service hold`() {
        // Negative control: a 4xx must NOT be reported as the service being down.
        assertEquals(Freshness.STALE, FreshnessClassifier.classify(http(404), online = true))
    }

    @Test fun `an unreachable host is offline`() {
        assertEquals(Freshness.OFFLINE, FreshnessClassifier.classify(UnknownHostException(), online = true))
        assertEquals(Freshness.OFFLINE, FreshnessClassifier.classify(ConnectException("refused"), online = true))
    }

    @Test fun `other online failures are stale, not falsely offline`() {
        // Negative control: a generic IO error while online is not evidence of no network.
        assertEquals(Freshness.STALE, FreshnessClassifier.classify(IOException("read"), online = true))
        assertEquals(Freshness.STALE, FreshnessClassifier.classify(IllegalStateException("parse"), online = true))
    }
}
