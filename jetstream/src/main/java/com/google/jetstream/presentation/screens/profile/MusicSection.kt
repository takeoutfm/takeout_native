package com.google.jetstream.presentation.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.google.jetstream.R
import com.google.jetstream.data.listen.ListenBrainzAccount
import com.google.jetstream.data.listen.ListenBrainzUser
import com.google.jetstream.data.listen.TokenHandoff
import com.google.jetstream.data.takeout.TakeoutSettings
import com.google.jetstream.presentation.common.QrCode
import com.google.jetstream.presentation.theme.JetStreamCardShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the "connect ListenBrainz from your phone" dialog is. */
sealed interface Handoff {
    data object Closed : Handoff
    /** No local network address to serve the page on. */
    data object NoNetwork : Handoff
    data class Waiting(val url: String) : Handoff
    data class Connected(val user: ListenBrainzUser) : Handoff
}

@HiltViewModel
class MusicSectionViewModel @Inject constructor(
    private val settings: TakeoutSettings,
    private val listenBrainz: ListenBrainzAccount,
) : ViewModel() {
    val reportPlays = settings.reportPlays
    val submitListens = settings.submitListens
    val user: StateFlow<ListenBrainzUser?> = listenBrainz.user

    fun setReportPlays(on: Boolean) = settings.setReportPlays(on)
    fun setSubmitListens(on: Boolean) = settings.setSubmitListens(on)
    fun disconnect() {
        listenBrainz.disconnect()
        settings.setSubmitListens(false)
    }

    private val _handoff = MutableStateFlow<Handoff>(Handoff.Closed)
    val handoff: StateFlow<Handoff> = _handoff.asStateFlow()
    private var server: TokenHandoff? = null

    fun startConnect() {
        server?.stop()
        val handoff = TokenHandoff(
            title = "Connect ListenBrainz",
            hint = "Paste your ListenBrainz user token. Find it under \"User token\" at",
            helpUrl = ListenBrainzAccount.SettingsUrl,
        ) { value ->
            val user = listenBrainz.connect(value)
            if (user == null) {
                val message =
                    "That token wasn't accepted. Copy it again from your ListenBrainz settings."
                message to false
            } else {
                settings.setSubmitListens(true)
                _handoff.value = Handoff.Connected(user)
                "Connected as ${user.name}. You can close this page." to true
            }
        }
        server = handoff
        _handoff.value = handoff.start(viewModelScope)?.let { Handoff.Waiting(it) }
            ?: Handoff.NoNetwork
    }

    fun closeConnect() {
        server?.stop()
        server = null
        _handoff.value = Handoff.Closed
    }

    override fun onCleared() {
        server?.stop()
    }
}

/** Music settings: reporting plays to Takeout and to ListenBrainz (connected from a phone). */
@Composable
fun MusicSection(viewModel: MusicSectionViewModel = hiltViewModel()) {
    val reportPlays by viewModel.reportPlays.collectAsStateWithLifecycle()
    val submitListens by viewModel.submitListens.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val handoff by viewModel.handoff.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.padding(horizontal = 72.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.music_settings),
            style = MaterialTheme.typography.headlineSmall,
        )
        SettingSwitch(
            title = stringResource(R.string.music_report_plays),
            description = stringResource(R.string.music_report_plays_desc),
            checked = reportPlays,
            onChange = viewModel::setReportPlays,
        )
        val connected = user
        if (connected == null) {
            SettingItem(
                title = stringResource(R.string.listenbrainz_connect),
                description = stringResource(R.string.listenbrainz_connect_desc),
                onClick = viewModel::startConnect,
            )
        } else {
            SettingSwitch(
                title = stringResource(R.string.listenbrainz_submit),
                description = stringResource(R.string.listenbrainz_connected_as, connected.name),
                checked = submitListens,
                onChange = viewModel::setSubmitListens,
            )
            SettingItem(
                title = stringResource(R.string.listenbrainz_disconnect),
                description = stringResource(R.string.listenbrainz_disconnect_desc),
                onClick = viewModel::disconnect,
            )
        }
    }

    if (handoff != Handoff.Closed) {
        HandoffDialog(handoff = handoff, onDismiss = viewModel::closeConnect)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        ),
        shape = ListItemDefaults.shape(shape = JetStreamCardShape),
    )
}

@Composable
private fun SettingItem(title: String, description: String, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        ),
        shape = ListItemDefaults.shape(shape = JetStreamCardShape),
    )
}

/** Scan with a phone, paste the token there; this updates when the TV accepts it. */
@Composable
private fun HandoffDialog(handoff: Handoff, onDismiss: () -> Unit) {
    // Wider than a platform dialog allows by default.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val done = remember { FocusRequester() }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .width(760.dp)
                .background(MaterialTheme.colorScheme.surface, JetStreamCardShape)
                .padding(28.dp),
        ) {
            Text(
                text = stringResource(R.string.listenbrainz_connect),
                style = MaterialTheme.typography.headlineSmall,
            )
            when (handoff) {
                is Handoff.Waiting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    QrCode(
                        text = handoff.url,
                        contentDescription = handoff.url,
                        modifier = Modifier.size(220.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.listenbrainz_scan),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = handoff.url,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.listenbrainz_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.alpha(0.75f),
                        )
                    }
                }
                is Handoff.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Text(
                        text = stringResource(
                            R.string.listenbrainz_connected_as, handoff.user.name
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Handoff.NoNetwork -> Text(
                    text = stringResource(R.string.listenbrainz_no_network),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Handoff.Closed -> Box(Modifier)
            }
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(done)) {
                Text(
                    stringResource(
                        if (handoff is Handoff.Connected) R.string.done else R.string.cancel
                    )
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { done.requestFocus() } }
    }
}
