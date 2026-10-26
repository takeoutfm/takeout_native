package com.takeoutfm.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.music.MusicPage
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinkUiState(
    val code: String = "",
    val user: String = "",
    val password: String = "",
    val passcode: String = "",
    val busy: Boolean = false,
    /** A string resource: the outcome or a problem. */
    val message: Int? = null,
    val linked: Boolean = false,
)

@HiltViewModel
class LinkViewModel @Inject constructor(
    private val api: TakeoutApi,
    private val session: TakeoutSession,
) : ViewModel() {
    private val _ui = MutableStateFlow(LinkUiState())
    val ui: StateFlow<LinkUiState> = _ui.asStateFlow()

    fun update(change: (LinkUiState) -> LinkUiState) = _ui.update { change(it).copy(message = null, linked = false) }

    fun link() {
        val s = _ui.value
        if (s.code.isBlank() || s.user.isBlank() || s.password.isEmpty()) {
            return _ui.update { it.copy(message = R.string.error_no_credentials) }
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            val result = try {
                if (api.link(session.endpoint, s.code, s.user.trim(), s.password, s.passcode)) R.string.link_success else R.string.link_rejected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.error_unreachable
            }
            _ui.update {
                it.copy(
                    busy = false,
                    message = result,
                    linked = result == R.string.link_success,
                    // Don't keep the password around once it's done its job.
                    password = if (result == R.string.link_success) "" else it.password,
                    code = if (result == R.string.link_success) "" else it.code,
                )
            }
        }
    }
}

/** Link: sign in a TV (or a new device) by approving the code it shows, as the Flutter app. */
@Composable
fun LinkScreen(onBack: () -> Unit, viewModel: LinkViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    MusicPage(title = stringResource(R.string.link_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.link_intro))
            OutlinedTextField(
                value = ui.code,
                onValueChange = { v -> viewModel.update { it.copy(code = v) } },
                label = { Text(stringResource(R.string.code_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.user,
                onValueChange = { v -> viewModel.update { it.copy(user = v) } },
                label = { Text(stringResource(R.string.user_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.password,
                onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                label = { Text(stringResource(R.string.password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.passcode,
                onValueChange = { v -> viewModel.update { it.copy(passcode = v) } },
                label = { Text(stringResource(R.string.passcode_label)) },
                supportingText = { Text(stringResource(R.string.passcode_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.link() }),
                modifier = Modifier.fillMaxWidth(),
            )
            ui.message?.let {
                Text(
                    stringResource(it, ""),
                    color = if (ui.linked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = viewModel::link, enabled = !ui.busy, modifier = Modifier.align(Alignment.End)) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.link_label))
            }
        }
    }
}
