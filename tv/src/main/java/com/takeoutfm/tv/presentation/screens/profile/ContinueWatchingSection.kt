package com.takeoutfm.tv.presentation.screens.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.takeout.TakeoutSettings
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ContinueWatchingSectionViewModel @Inject constructor(
    private val settings: TakeoutSettings,
) : ViewModel() {
    val days = settings.continueWatchingDays

    fun select(days: Int) = settings.setContinueWatchingDays(days)
}

/** Picks how far back the Home screen's Continue Watching rows look. */
@Composable
fun ContinueWatchingSection(
    viewModel: ContinueWatchingSectionViewModel = hiltViewModel(),
) {
    val selectedDays by viewModel.days.collectAsStateWithLifecycle()

    Column(modifier = Modifier.padding(horizontal = 72.dp)) {
        Text(
            text = stringResource(R.string.continue_watching),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.continue_watching_setting_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .alpha(0.75f)
        )
        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(TakeoutSettings.ContinueWatchingDayOptions) { days ->
                ListItem(
                    modifier = Modifier.padding(bottom = 16.dp),
                    selected = days == selectedDays,
                    onClick = { viewModel.select(days) },
                    headlineContent = {
                        Text(
                            text = if (days == TakeoutSettings.AllTime) {
                                stringResource(R.string.continue_watching_all_time)
                            } else {
                                pluralStringResource(R.plurals.continue_watching_last_days, days, days)
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    trailingContent = if (days == selectedDays) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                    ),
                    shape = ListItemDefaults.shape(shape = JetStreamCardShape)
                )
            }
        }
    }
}
