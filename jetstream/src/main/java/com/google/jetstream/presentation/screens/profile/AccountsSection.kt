/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jetstream.presentation.screens.profile

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.jetstream.data.takeout.SessionState
import com.google.jetstream.data.takeout.TakeoutSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.google.jetstream.data.util.StringConstants
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding

@Immutable
data class AccountsSectionData(
    val title: String,
    val value: String? = null,
    val onClick: () -> Unit = {}
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val session: TakeoutSession,
) : ViewModel() {
    val state: StateFlow<SessionState> = session.state
    fun signOut() = session.signOut()
}

@Composable
fun AccountsSection(viewModel: AccountViewModel = hiltViewModel()) {
    val childPadding = rememberChildPadding()
    val session by viewModel.state.collectAsStateWithLifecycle()
    val items = listOf(
        AccountsSectionData(
            title = "Server",
            value = session.endpoint.ifEmpty { "Not connected" },
        ),
        AccountsSectionData(
            title = "Sign out",
            value = "Sign out of Takeout on this device",
            onClick = viewModel::signOut,
        ),
    )
    LazyVerticalGrid(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = childPadding.start),
        columns = GridCells.Fixed(2),
        content = {
            items(items.size) { index ->
                AccountsSelectionItem(
                    key = index,
                    accountsSectionData = items[index]
                )
            }
        }
    )
}
