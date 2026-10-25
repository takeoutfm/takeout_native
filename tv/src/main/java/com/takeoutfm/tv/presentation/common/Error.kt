/*
 * Copyright 2024 Google LLC
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

package com.takeoutfm.tv.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.takeoutfm.tv.R

/** A centred "couldn't load" message, with a focused Try again button when [onRetry] is given. */
@Composable
fun Error(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.message_error),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.message_error_detail),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.alpha(0.75f)
            )
            if (onRetry != null) {
                val focusRequester = remember { FocusRequester() }
                Button(onClick = onRetry, modifier = Modifier.focusRequester(focusRequester)) {
                    Text(stringResource(R.string.try_again))
                }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }
        }
    }
}
