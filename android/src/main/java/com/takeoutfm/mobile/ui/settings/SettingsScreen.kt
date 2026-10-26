package com.takeoutfm.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.mobile.R
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.listen.ListenBrainzAccount
import com.takeoutfm.shared.listen.ListenBrainzUser
import com.takeoutfm.shared.settings.PlaySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: PlaySettings,
    private val listenBrainz: ListenBrainzAccount,
    session: TakeoutSession,
) : ViewModel() {
    val endpoint: String = session.endpoint
    val user: StateFlow<ListenBrainzUser?> = listenBrainz.user

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    /** Checks the pasted token with ListenBrainz and keeps it if valid. */
    fun connect(token: String) {
        viewModelScope.launch {
            _connecting.value = true
            _error.value = try {
                if (listenBrainz.connect(token) == null) R.string.setting_listenbrainz_rejected else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.setting_listenbrainz_unreachable
            }
            _connecting.value = false
        }
    }

    fun disconnect() = listenBrainz.disconnect()
}

/** The Flutter app's settings: Track Activity and ListenBrainz (paste the token here). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val reportPlays by viewModel.settings.reportPlays.collectAsStateWithLifecycle()
    val playbackCaching by viewModel.settings.playbackCaching.collectAsStateWithLifecycle()
    val submitListens by viewModel.settings.submitListens.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_label)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.setting_server)) },
                supportingContent = { Text(viewModel.endpoint) },
            )
            HorizontalDivider()
            SwitchItem(
                icon = Icons.Filled.DownloadForOffline,
                title = stringResource(R.string.setting_auto_cache_title),
                subtitle = stringResource(R.string.setting_auto_cache_subtitle),
                checked = playbackCaching,
                onChange = viewModel.settings::setPlaybackCaching,
            )
            SwitchItem(
                icon = Icons.Filled.Timeline,
                title = stringResource(R.string.setting_track_activity_title),
                subtitle = stringResource(R.string.setting_track_activity_subtitle),
                checked = reportPlays,
                onChange = viewModel.settings::setReportPlays,
            )
            val connected = user
            if (connected != null) {
                SwitchItem(
                    icon = Icons.Filled.Key,
                    title = stringResource(R.string.setting_listenbrainz_title),
                    subtitle = stringResource(R.string.setting_listenbrainz_connected, connected.name),
                    checked = submitListens,
                    onChange = viewModel.settings::setSubmitListens,
                )
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.padding(start = 72.dp, bottom = 8.dp),
                ) { Text(stringResource(R.string.setting_listenbrainz_disconnect)) }
            } else {
                ListenBrainzConnect(viewModel)
            }
        }
    }
}

@Composable
private fun ListenBrainzConnect(viewModel: SettingsViewModel) {
    var token by remember { mutableStateOf("") }
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    ListItem(
        leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.setting_listenbrainz_title)) },
        supportingContent = { Text(stringResource(R.string.setting_listenbrainz_subtitle)) },
    )
    Column(
        Modifier.padding(start = 72.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.setting_listenbrainz_token)) },
            supportingText = {
                Text(
                    error?.let { stringResource(it) }
                        ?: stringResource(R.string.setting_listenbrainz_token_hint, ListenBrainzAccount.SettingsUrl),
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.connect(token) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.connect(token) }, enabled = token.isNotBlank() && !connecting) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.setting_listenbrainz_connect))
                }
            }
        }
    }
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
