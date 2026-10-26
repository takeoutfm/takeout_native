package com.takeoutfm.mobile.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.mobile.R
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.TakeoutLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An error to show, as a string resource with an optional argument. */
data class SignInError(val message: Int, val arg: String? = null)

data class SignInUiState(
    val host: String,
    val user: String = "",
    val password: String = "",
    val passcode: String = "",
    /** Device-code sign-in instead of a password. */
    val withCode: Boolean = false,
    val busy: Boolean = false,
    /** The code to approve on a signed-in device, and where. */
    val code: String? = null,
    val link: String? = null,
    val error: SignInError? = null,
)

/**
 * Sign-in to a Takeout server: user name and password (plus a passcode for two-factor
 * accounts), or a device code approved on another signed-in device.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val api: TakeoutApi,
    private val session: TakeoutSession,
    private val library: TakeoutLibrary,
) : ViewModel() {
    private val _ui = MutableStateFlow(SignInUiState(host = session.endpoint.ifEmpty { DefaultHost }))
    val ui: StateFlow<SignInUiState> = _ui.asStateFlow()

    private var job: Job? = null

    fun update(change: (SignInUiState) -> SignInUiState) = _ui.update { change(it).copy(error = null) }

    fun toggleMode() {
        job?.cancel()
        _ui.update { it.copy(withCode = !it.withCode, busy = false, code = null, link = null, error = null) }
    }

    fun signIn() {
        val state = _ui.value
        val endpoint = TakeoutApi.normalizeEndpoint(state.host)
        when {
            endpoint.isEmpty() -> return fail(SignInError(R.string.error_no_host))
            state.user.isBlank() || state.password.isEmpty() ->
                return fail(SignInError(R.string.error_no_credentials))
        }
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                val tokens = api.login(endpoint, state.user.trim(), state.password, state.passcode)
                if (tokens == null) {
                    fail(SignInError(R.string.error_rejected))
                } else {
                    signedIn(endpoint, tokens)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SignInError(R.string.error_unreachable, endpoint))
            }
        }
    }

    fun getCode() {
        val endpoint = TakeoutApi.normalizeEndpoint(_ui.value.host)
        if (endpoint.isEmpty()) return fail(SignInError(R.string.error_no_host))
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(busy = true, code = null, link = null, error = null) }
            try {
                val accessCode = api.requestCode(endpoint)
                if (accessCode.code.isEmpty() || accessCode.accessToken.isEmpty()) {
                    return@launch fail(SignInError(R.string.error_unreachable, endpoint))
                }
                _ui.update { it.copy(busy = false, code = accessCode.code, link = "$endpoint/link") }
                repeat(MaxPolls) {
                    delay(PollIntervalMs)
                    val tokens = try {
                        api.checkCode(endpoint, accessCode)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // transient network error: keep polling
                    }
                    if (tokens != null) return@launch signedIn(endpoint, tokens)
                }
                _ui.update { it.copy(code = null, link = null) }
                fail(SignInError(R.string.error_code_expired))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SignInError(R.string.error_unreachable, endpoint))
            }
        }
    }

    private suspend fun signedIn(endpoint: String, tokens: com.takeoutfm.shared.model.ApiTokens) {
        session.signIn(endpoint, tokens)
        library.refresh()
    }

    private fun fail(error: SignInError) = _ui.update { it.copy(busy = false, error = error) }

    private companion object {
        const val DefaultHost = "https://takeout.fm"
        const val PollIntervalMs = 3_000L
        const val MaxPolls = 200 // ~10 minutes
    }
}
