package com.takeoutfm.tv.presentation.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.shared.api.DeviceCode
import com.takeoutfm.shared.api.SessionState
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.progress.TakeoutProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-level: exposes whether we're signed in so the UI can gate on it. */
@HiltViewModel
class SessionViewModel @Inject constructor(
    session: TakeoutSession,
    private val library: TakeoutLibrary,
    private val progress: TakeoutProgress,
    private val listens: ListenReporter,
) : ViewModel() {
    val state: StateFlow<SessionState> = session.state

    /**
     * On launch, sign-in and return to the foreground: refreshes the library (skipped if done in
     * the last 30 s) and resends watch progress and play reports saved while offline.
     */
    fun refreshLibrary() {
        viewModelScope.launch { library.refresh(maxAgeMs = 30_000) }
        viewModelScope.launch { progress.sync() }
        listens.retry()
    }
}

data class LoginUiState(
    val endpoint: String,
    val busy: Boolean = false,
    /** The code to approve at [DeviceCode.link]. */
    val code: DeviceCode? = null,
    /** [code] is no longer valid (its time ran out, or the server forgot it). */
    val expired: Boolean = false,
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
            _ui.update { it.copy(busy = true, code = null, expired = false, error = null) }
            val code = try {
                api.requestCode(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch _ui.update { it.copy(busy = false, error = "Couldn't reach $endpoint") }
            }
            _ui.update { it.copy(busy = false, code = code) }
            val tokens = api.awaitCode(code) ?: return@launch _ui.update { it.copy(expired = true) }
            _ui.update { it.copy(code = null) } // not shown again after a sign-out
            session.signIn(endpoint, tokens)
        }
    }

    private companion object {
        const val DefaultEndpoint = "https://takeout.fm"
    }
}
