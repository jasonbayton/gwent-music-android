package org.bayton.gmc.ui.login

import android.content.Context
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.BuildConfig
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.LoopbackAuth
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import org.bayton.gmc.notify.NotificationScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject

data class LoginState(val loading: Boolean = false, val error: String? = null, val done: Boolean = false)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: LoopbackAuth,
    private val tokens: TokenStore,
    private val session: Session,
    private val repo: GwentRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    val mockAvailable = BuildConfig.USE_MOCK

    fun startLogin(openInBrowser: (String) -> Unit) = viewModelScope.launch {
        _state.value = LoginState(loading = true)
        val t = try {
            auth.login(openInBrowser)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Browser/transport setup can fail before contact resolution gets a chance to report it.
            _state.value = LoginState(error = "Couldn't complete sign-in. Please try again.")
            return@launch
        }
        if (t?.accessToken == null) { _state.value = LoginState(error = "Sign-in was not completed."); return@launch }
        tokens.save(t)
        val claims = t.idToken?.let(::decode)
        val email = claims?.optJSONArray("emails")?.optString(0)?.ifBlank { null } ?: claims?.optString("email")?.ifBlank { null }
        val oid = claims?.optString("oid")?.ifBlank { null }
        resolve(email, oid)
    }

    /** Debug only: skip OAuth and drive the seeded mock server. */
    fun mockSignIn() {
        tokens.accessToken = "mock-access-token"
        _state.value = LoginState(loading = true)
        resolve(email = "parent@example.test", oid = null)
    }

    private fun resolve(email: String?, oid: String?) = viewModelScope.launch {
        val result = runCatching { repo.resolveContact(email, oid) }
        result.onSuccess { cid ->
            if (cid != null) {
                // Sign-out cancels periodic work; Application.onCreate will not run again on re-login.
                NotificationScheduler.schedulePeriodic(appContext)
                NotificationScheduler.syncNow(appContext) // baseline sync + plan reminders straight after sign-in
                _state.value = LoginState(done = true)
            } else {
                // A genuine empty result: the service returned no contact for this identity.
                _state.value = LoginState(error = "Signed in, but no Gwent Music account was found for this login.")
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A service/network failure while verifying is NOT proof the account is missing - offer retry.
            _state.value = LoginState(error = "Signed in, but we couldn't reach Gwent Music to verify your account. Please check your connection and try again.")
        }
    }

    private fun decode(jwt: String): JSONObject? = runCatching {
        val payload = jwt.split(".")[1]
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
    }.getOrNull()

}
