package org.bayton.gmc.data

import android.app.Application
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bayton.gmc.data.auth.*
import org.bayton.gmc.di.NetworkModule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real credential persistence and OkHttp interception, with a terminal fake transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BearerGateTest {
    private lateinit var tokens: TokenStore
    private val now = 1_800_000_000_000L
    private var invalidations = 0
    private var refreshes = 0
    private val sent = mutableListOf<Request>()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("test-auth", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokens = TokenStore(prefs)
    }

    private fun save(access: String = "access", refresh: String? = "refresh", expires: Long = 3600) =
        tokens.save(TokenResult(access, refresh, null, expires), now)

    private fun gate(result: RefreshResult = RefreshResult.Rejected, mock: Boolean = false) =
        BearerGate(tokens, { refreshes++; result }, { invalidations++ }, mock) { now }

    private fun client(gate: BearerGate, vararg codes: Int) = NetworkModule.client(gate) { "synthetic-key" }
        .newBuilder().addInterceptor { chain ->
            sent += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(codes.getOrElse(sent.lastIndex) { 200 }).message("test")
                .body("{}".toResponseBody()).build()
        }.build()

    private fun call(client: OkHttpClient) = client.newCall(Request.Builder().url("https://fixture.invalid/api").build()).execute().close()

    @Test fun `missing or blank bearer never reaches transport even with key`() {
        for (value in listOf(null, "", " ")) {
            tokens.accessToken = value
            assertThrows(ReauthenticationRequired::class.java) { call(client(gate())) }
        }
        assertTrue(sent.isEmpty())
        assertEquals(3, invalidations)
    }

    @Test fun `fresh credentials allow initial contact lookup without contact session`() {
        save()
        call(client(gate()))
        assertEquals("Bearer access", sent.single().header("Authorization"))
        assertEquals("synthetic-key", sent.single().header("ApiKey"))
        assertEquals(0, refreshes)
    }

    @Test fun `revoked refresh blocks subsequent key-only requests`() {
        save()
        val client = client(gate(), 401)
        assertThrows(ReauthenticationRequired::class.java) { call(client) }
        assertThrows(ReauthenticationRequired::class.java) { call(client) }
        assertEquals(1, sent.size)
        assertNull(tokens.accessToken)
    }

    @Test fun `refresh rotates bearer and retains omitted refresh token`() {
        save()
        call(client(gate(RefreshResult.Success(TokenResult("new", null, null, 3600))), 401, 200))
        assertEquals(listOf("Bearer access", "Bearer new"), sent.map { it.header("Authorization") })
        assertEquals("refresh", tokens.refreshToken)
    }

    @Test fun `second unauthorised response invalidates rather than looping`() {
        save()
        assertThrows(ReauthenticationRequired::class.java) {
            call(client(gate(RefreshResult.Success(TokenResult("new", "rotated", null, 3600))), 401, 401))
        }
        assertEquals(2, sent.size)
        assertEquals(1, refreshes)
        assertNull(tokens.refreshToken)
    }

    @Test fun `outage preserves credentials but does not retry protected request`() {
        save()
        val client = client(gate(RefreshResult.Unavailable), 401, 200)
        assertThrows(AuthenticationUnavailable::class.java) { call(client) }
        assertThrows(AuthenticationUnavailable::class.java) { call(client) }
        assertEquals(1, sent.size)
        assertEquals(2, refreshes)
        assertEquals("refresh", tokens.refreshToken)
        assertEquals(0, invalidations)
    }

    @Test fun `expiry proactively refreshes even when provider would return success`() {
        save(expires = 1)
        call(client(gate(RefreshResult.Success(TokenResult("new", null, null, 3600)))))
        assertEquals("Bearer new", sent.single().header("Authorization"))
        assertEquals(1, refreshes)
    }

    @Test fun `expired bearer and refresh outage cause no protected traffic`() {
        save(expires = 1)
        assertThrows(AuthenticationUnavailable::class.java) { call(client(gate(RefreshResult.Unavailable))) }
        assertTrue(sent.isEmpty())
        assertEquals("refresh", tokens.refreshToken)
    }

    @Test fun `mock opaque token works only in explicitly allowed mock mode`() {
        tokens.accessToken = "mock-access-token"
        call(client(gate(mock = true)))
        sent.clear()
        assertThrows(ReauthenticationRequired::class.java) { call(client(gate())) }
        assertTrue(sent.isEmpty())
    }

    @Test fun `stale unauthorised response reuses refresh completed by sibling`() {
        save()
        val gate = gate(RefreshResult.Success(TokenResult("new", null, null, 3600)))
        val old = gate.credentials()
        val fresh = gate.rejected(old)
        assertEquals(fresh, gate.rejected(old))
        assertEquals(1, refreshes)
    }

    @Test fun `late refresh cannot resurrect logout or overwrite newer login`() {
        for (newLogin in listOf(false, true)) {
            save()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gate = BearerGate(tokens, {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                RefreshResult.Success(TokenResult("late", null, null, 3600))
            }, { invalidations++ }) { now }
            val old = gate.credentials()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Boolean> {
                    assertThrows(ReauthenticationRequired::class.java) { gate.rejected(old) }; true
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                tokens.clear()
                if (newLogin) save("other-account")
                release.countDown()
                assertTrue(future.get(5, TimeUnit.SECONDS))
                assertEquals(if (newLogin) "other-account" else null, tokens.accessToken)
            } finally { release.countDown(); executor.shutdownNow() }
        }
        assertEquals(0, invalidations)
    }

    @Test fun `response after logout is not handed to repository`() {
        save()
        val client = NetworkModule.client(gate()) { "synthetic-key" }.newBuilder().addInterceptor { chain ->
            tokens.clear()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("test").body("{}".toResponseBody()).build()
        }.build()
        assertThrows(ReauthenticationRequired::class.java) { call(client) }
    }

    @Test fun `login after refresh replacement cannot supply credentials to old request`() {
        save()
        val store = spy(tokens)
        val old = store.snapshot()
        val fresh = TokenResult("fresh", null, null, 3600)
        val gate = BearerGate(store, { RefreshResult.Success(fresh) }, { invalidations++ }) { now }
        // Call the renewal path directly with an expired snapshot to place the interleaving
        // immediately after replacement, rather than before the compare-and-set.
        store.expire(old)
        val expired = store.snapshot()
        doAnswer { invocation ->
            val replacement = invocation.callRealMethod()
            store.save(TokenResult("other-account", "other-refresh", null, 3600), now)
            replacement
        }.`when`(store).replace(expired, fresh, now)
        assertThrows(ReauthenticationRequired::class.java) { call(client(gate)) }
        assertTrue(sent.isEmpty())
        assertEquals("other-account", store.accessToken)
    }

    @Test fun `token endpoint distinguishes invalid grant from outages`() {
        fun refresh(code: Int, body: String): RefreshResult {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("test").body(body.toResponseBody()).build()
            }.build()
            return LoopbackAuth(RuntimeEnvironment.getApplication(), http, "https://fixture.invalid/token").refreshBlocking("test")
        }
        assertEquals(RefreshResult.Rejected, refresh(400, "{\"error\":\"invalid_grant\"}"))
        assertEquals(RefreshResult.Unavailable, refresh(503, "unavailable"))
        assertEquals(RefreshResult.Unavailable, refresh(429, "{}"))
        assertEquals(RefreshResult.Unavailable, refresh(200, "{}"))
        val success = refresh(200, "{\"access_token\":\"new\",\"expires_in\":3600}") as RefreshResult.Success
        assertEquals(3600L, success.tokens.expiresIn)
    }
}
