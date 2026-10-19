package com.google.jetstream.presentation.screens.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.google.jetstream.presentation.theme.JetStreamCardShape

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Sign in to Takeout",
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Enter your server, then approve the code from your phone or computer.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))

        Text(text = "Server", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = { fieldFocus.requestFocus() },
            shape = ClickableSurfaceDefaults.shape(shape = JetStreamCardShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                focusedContainerColor = MaterialTheme.colorScheme.inverseOnSurface,
                pressedContainerColor = MaterialTheme.colorScheme.inverseOnSurface,
                focusedContentColor = MaterialTheme.colorScheme.onSurface,
                pressedContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    shape = JetStreamCardShape,
                )
            ),
            modifier = Modifier.width(560.dp),
        ) {
            BasicTextField(
                value = ui.endpoint,
                onValueChange = viewModel::onEndpointChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .focusRequester(fieldFocus),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::getCode, enabled = !ui.busy) {
            Text(if (ui.code == null) "Get code" else "New code")
        }

        val code = ui.code
        if (code != null) {
            Spacer(Modifier.height(32.dp))
            Text(text = code, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "On your phone or computer, go to ${ui.link} and enter this code. " +
                    "This screen will continue automatically.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        val error = ui.error
        if (error != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
