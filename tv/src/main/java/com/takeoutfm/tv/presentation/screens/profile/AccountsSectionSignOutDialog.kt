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

package com.takeoutfm.tv.presentation.screens.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import com.takeoutfm.tv.tvmaterial.StandardDialog

@OptIn(
    ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class,
    ExperimentalTvMaterial3Api::class
)
/** Asks before signing out (SIGN-10); Cancel has focus, so a stray press keeps the sign-in. */
@Composable
fun AccountsSectionSignOutDialog(
    showDialog: Boolean,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    StandardDialog(
        showDialog = showDialog,
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        confirmButton = {
            AccountsSectionDialogButton(
                modifier = Modifier.padding(start = 8.dp),
                text = stringResource(R.string.sign_out),
                shouldRequestFocus = false,
                onClick = onConfirm
            )
        },
        dismissButton = {
            AccountsSectionDialogButton(
                modifier = Modifier.padding(end = 8.dp),
                text = stringResource(R.string.cancel),
                shouldRequestFocus = true,
                onClick = onDismissRequest
            )
        },
        title = {
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = stringResource(R.string.sign_out_title),
                color = MaterialTheme.colorScheme.surface,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                modifier = Modifier.padding(horizontal = 8.dp),
                text = stringResource(R.string.sign_out_detail),
                color = MaterialTheme.colorScheme.surface,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        containerColor = MaterialTheme.colorScheme.onSurface,
        shape = JetStreamCardShape
    )
}
