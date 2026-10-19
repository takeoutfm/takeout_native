package com.google.jetstream.presentation.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.jetstream.data.takeout.SessionState
import com.google.jetstream.data.takeout.TakeoutApi
import com.google.jetstream.data.takeout.TakeoutSession
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-level: exposes whether we're signed in so the UI can gate on it. */
@HiltViewModel
class SessionViewModel @Inject constructor(
    session: TakeoutSession,
) : ViewModel() {
    val state: StateFlow<SessionState> = session.state
}

data class LoginUiState(
    val endpoint: String,
    val busy: Boolean = false,
    val code: String? = null,
    val link: String? = null,
    val error: String? = null,
)

/**
 * Device-code sign-in: show a short code, the user approves it at <endpoint>/link from a phone or
 * computer, and we poll until the server hands back tokens. No typing of credentials on the TV.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val api: TakeoutApi,
    private val session: TakeoutSession,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        LoginUiState(endpoint = session.endpoint.ifEmpty { DefaultEndpoint })
    )
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null

    fun onEndpointChange(value: String) {
        _ui.update { it.copy(endpoint = value, error = null) }
    }

    fun getCode() {
        pollJob?.cancel()
        val endpoint = TakeoutApi.normalizeEndpoint(_ui.value.endpoint)
        if (endpoint.isEmpty()) {
            _ui.update { it.copy(error = "Enter your Takeout server address") }
            return
        }
        pollJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true, code = null, link = null, error = null) }
            try {
                val accessCode = api.requestCode(endpoint)
                if (accessCode.code.isEmpty() || accessCode.accessToken.isEmpty()) {
                    throw IOException("Server returned no code")
                }
                _ui.update {
                    it.copy(busy = false, code = accessCode.code, link = "$endpoint/link")
                }
                repeat(MaxPolls) {
                    delay(PollIntervalMs)
                    val tokens = try {
                        api.checkCode(endpoint, accessCode)
                    } catch (e: IOException) {
                        null // transient network error: keep polling
                    }
                    if (tokens != null) {
                        session.signIn(endpoint, tokens)
                        return@launch
                    }
                }
                _ui.update { it.copy(code = null, link = null, error = "Code expired. Get a new code.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, code = null, link = null, error = "Couldn't reach $endpoint")
                }
            }
        }
    }

    private companion object {
        const val DefaultEndpoint = "https://takeout.fm"
        const val PollIntervalMs = 3_000L
        const val MaxPolls = 200 // ~10 minutes
    }
}
