package org.bayton.gmc.data.auth

import android.app.Application
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Real loopback sockets and PKCE, but an intercepted token exchange with synthetic credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class LoopbackGapTest {
    private data class FlowResult(val tokens: TokenResult?, val authorize: Uri, val forms: List<Map<String, String>>)

    private fun flow(status: Int = 200,
        body: String = """{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_in":3600}""",
        paths: (Uri) -> List<String>): FlowResult {
        val forms = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://tokens.example.test/exchange", chain.request().url.toString())
            val form = chain.request().body as FormBody
            forms += (0 until form.size).associate { form.name(it) to form.value(it) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val auth = LoopbackAuth(RuntimeEnvironment.getApplication(), client, "https://tokens.example.test/exchange")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val browser = Executors.newSingleThreadExecutor()
        var authorize: Uri? = null
        var browserTask: Future<*>? = null
        val result = scope.async {
            auth.login { url ->
                val uri = Uri.parse(url)
                authorize = uri
                val port = Uri.parse(uri.getQueryParameter("redirect_uri")).port
                browserTask = browser.submit {
                    paths(uri).forEach { path ->
                        Socket("127.0.0.1", port).use { socket ->
                            socket.soTimeout = 2000
                            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                            assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
                        }
                    }
                }
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!result.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Local OAuth flow must finish within five seconds", result.isCompleted)
            browserTask?.get(2, TimeUnit.SECONDS)
            return FlowResult(runBlocking { result.await() }, requireNotNull(authorize), forms.toList())
        } finally { scope.cancel(); browser.shutdownNow(); client.connectionPool.evictAll() }
    }

    private fun callback(uri: Uri) = "/?code=synthetic%2Bcode%3D&state=${uri.getQueryParameter("state")}"

    @Test fun `valid callback exchanges matching redirect and PKCE verifier exactly once`() {
        val result = flow(paths = { listOf(callback(it)) })
        assertEquals("synthetic-access", result.tokens?.accessToken)
        assertEquals(3600L, result.tokens?.expiresIn)
        val form = result.forms.single()
        assertEquals("synthetic+code=", form["code"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(result.authorize.getQueryParameter("redirect_uri"), form["redirect_uri"])
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(form.getValue("code_verifier").toByteArray()))
        assertEquals(challenge, result.authorize.getQueryParameter("code_challenge"))
        assertEquals("S256", result.authorize.getQueryParameter("code_challenge_method"))
        assertFalse(result.authorize.getQueryParameter("state").isNullOrBlank())
    }

    @Test fun `wrong state rejects callback without token exchange`() {
        val result = flow(paths = { listOf("/?code=synthetic&state=wrong") })
        assertNull(result.tokens)
        assertTrue(result.forms.isEmpty())
    }

    @Test fun `missing state rejects callback without token exchange`() {
        val result = flow(paths = { listOf("/?code=synthetic") })
        assertNull(result.tokens)
        assertTrue(result.forms.isEmpty())
    }

    @Test fun `provider cancellation completes without token exchange`() {
        val result = flow(paths = { listOf("/?error=access_denied&state=${it.getQueryParameter("state")}") })
        assertNull(result.tokens)
        assertTrue(result.forms.isEmpty())
    }

    @Test fun `favicon request does not consume the real callback`() {
        assertNotNull(flow(paths = { listOf("/favicon.ico", callback(it)) }).tokens)
    }

    @Test fun `failed token exchange does not claim sign in`() {
        assertNull(flow(status = 503, paths = { listOf(callback(it)) }).tokens)
    }

    @Test fun `malformed token response does not claim sign in`() {
        assertNull(flow(body = "not-json", paths = { listOf(callback(it)) }).tokens)
    }
}
