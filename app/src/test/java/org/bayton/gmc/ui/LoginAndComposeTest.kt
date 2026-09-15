package org.bayton.gmc.ui

import android.app.Application
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.*
import org.bayton.gmc.ui.login.LoginViewModel
import org.bayton.gmc.ui.messages.ComposeViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/** Synthetic identity and transport only: no credentials, browser, or provider traffic in CI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class LoginAndComposeTest {
    private val repo = mock(GwentRepository::class.java)
    private val auth = mock(LoopbackAuth::class.java)
    private val tokens = mock(TokenStore::class.java)
    private val session = mock(Session::class.java)
    private val browser: (String) -> Unit = { fail("Mock auth must not open a browser") }
    private val models = mutableListOf<ViewModel>()
    private fun login() = LoginViewModel(auth, tokens, session, repo, RuntimeEnvironment.getApplication()).also { models += it }
    private fun compose() = ComposeViewModel(repo).also { models += it }
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; drain() }
    private fun identity(json: String) = "header." + Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP) + ".signature"

    @Test fun `cancelled browser result does not persist credentials or query account`() = runBlocking<Unit> {
        `when`(auth.login(browser)).thenReturn(null)
        val vm = login(); vm.startLogin(browser); drain()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.done)
        assertEquals("Sign-in was not completed.", vm.state.value.error)
        verifyNoInteractions(tokens, repo)
    }

    @Test fun `identity token alone is not enough to resolve account`() = runBlocking<Unit> {
        `when`(auth.login(browser)).thenReturn(TokenResult(null, null, identity("{}"), 3600))
        val vm = login(); vm.startLogin(browser); drain()
        assertFalse(vm.state.value.done)
        verifyNoInteractions(tokens, repo)
    }

    @Test fun `identity claims are passed to account lookup after tokens saved`() = runBlocking<Unit> {
        val result = TokenResult("synthetic-access", "synthetic-refresh",
            identity("""{"emails":["parent@example.test"],"oid":"synthetic-oid"}"""), 3600)
        `when`(auth.login(browser)).thenReturn(result)
        val vm = login(); vm.startLogin(browser); drain()
        val saved = mockingDetails(tokens).invocations.single { it.method.name == "save" }
        val resolved = mockingDetails(repo).invocations.single { it.method.name == "resolveContact" }
        assertEquals(result, saved.arguments[0])
        assertTrue(saved.sequenceNumber < resolved.sequenceNumber)
        verify(repo).resolveContact("parent@example.test", "synthetic-oid")
        assertTrue(vm.state.value.error!!.contains("no Gwent Music account"))
    }

    @Test fun `malformed identity is a handled no match not a crash`() = runBlocking<Unit> {
        `when`(auth.login(browser)).thenReturn(TokenResult("synthetic", null, "not-a-jwt", 3600))
        val vm = login(); vm.startLogin(browser); drain()
        verify(repo).resolveContact(null, null)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.done)
    }

    @Test fun `lookup rejection offers retry rather than falsely claiming no account`() = runBlocking<Unit> {
        `when`(auth.login(browser)).thenReturn(TokenResult("synthetic", null, null, 3600))
        `when`(repo.resolveContact(null, null)).thenAnswer { throw ReauthenticationRequired() }
        val vm = login(); vm.startLogin(browser); drain()
        assertTrue(vm.state.value.error!!.contains("verify your account"))
        assertFalse(vm.state.value.error!!.contains("no Gwent Music account"))
        assertFalse(vm.state.value.done)
    }

    @Test fun `auth transport exception ends loading with retryable error`() = runBlocking<Unit> {
        `when`(auth.login(browser)).thenAnswer { throw IOException("synthetic outage") }
        val vm = login(); vm.startLogin(browser); drain()
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.done)
        verifyNoInteractions(tokens, repo)
    }

    @Test fun `cancelled auth remains cancellation`() = runBlocking<Unit> {
        `when`(auth.login(browser)).thenThrow(CancellationException("screen closed"))
        val vm = login(); val job = vm.startLogin(browser); drain()
        assertTrue(job.isCancelled)
        assertNull(vm.state.value.error)
        verifyNoInteractions(tokens, repo)
    }

    @Test fun `compose requires a recipient without sending`() {
        val vm = compose(); vm.send("Subject", "Body", " "); drain()
        assertNotNull(vm.ui.value.error)
        assertFalse(vm.ui.value.sent)
        verifyNoInteractions(repo)
    }

    @Test fun `successful send trims text and blocks repeat submission`() = runBlocking<Unit> {
        `when`(repo.sendMessage("Subject", "Body", "teacher")).thenReturn(true)
        val vm = compose(); vm.send(" Subject ", " Body ", "teacher"); drain()
        vm.send("Subject", "Body", "teacher"); drain()
        assertTrue(vm.ui.value.sent)
        verify(repo, times(1)).sendMessage("Subject", "Body", "teacher")
    }

    @Test fun `unconfirmed send warns to check Sent items`() = runBlocking<Unit> {
        `when`(repo.sendMessage("Subject", "Body", "teacher")).thenReturn(false)
        val vm = compose(); vm.send("Subject", "Body", "teacher"); drain()
        assertFalse(vm.ui.value.sent)
        assertFalse(vm.ui.value.sending)
        assertTrue(vm.ui.value.error!!.contains("Check your Sent items"))
    }

    @Test fun `transport exception does not claim successful send`() = runBlocking<Unit> {
        `when`(repo.sendMessage("Subject", "Body", "teacher")).thenAnswer { throw IOException("synthetic outage") }
        val vm = compose(); vm.send("Subject", "Body", "teacher"); drain()
        assertFalse(vm.ui.value.sent)
        assertTrue(vm.ui.value.error!!.contains("couldn't confirm"))
    }

    @Test fun `cancelled send remains cancellation and publishes no failure`() = runBlocking<Unit> {
        `when`(repo.sendMessage("Subject", "Body", "teacher")).thenThrow(CancellationException("screen closed"))
        val vm = compose(); val job = vm.send("Subject", "Body", "teacher"); drain()
        assertTrue(job.isCancelled)
        assertNull(vm.ui.value.error)
        assertFalse(vm.ui.value.sent)
    }
}
