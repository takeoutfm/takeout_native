package com.takeoutfm.mobile.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.mobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(viewModel: SignInViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = ui.host,
                    onValueChange = { v -> viewModel.update { it.copy(host = v) } },
                    label = { Text(stringResource(R.string.host_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.withCode) CodeSignIn(ui, viewModel) else PasswordSignIn(ui, viewModel)
                ui.error?.let { error ->
                    Text(
                        stringResource(error.message, error.arg.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = viewModel::toggleMode, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(
                        stringResource(
                            if (ui.withCode) R.string.sign_in_with_password else R.string.sign_in_with_code
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PasswordSignIn(ui: SignInUiState, viewModel: SignInViewModel) {
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
        keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
        modifier = Modifier.fillMaxWidth(),
    )
    BusyButton(ui.busy, stringResource(R.string.login_label), viewModel::signIn)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CodeSignIn(ui: SignInUiState, viewModel: SignInViewModel) {
    val code = ui.code
    if (code == null) {
        BusyButton(ui.busy, stringResource(R.string.get_code), viewModel::getCode)
    } else {
        Text(stringResource(R.string.code_instructions, ui.link.orEmpty()))
        Text(
            code,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.code_waiting),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        // Codes expire on the server; a fresh one replaces this one.
        TextButton(onClick = viewModel::getCode, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.get_new_code))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BusyButton(
    busy: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(label)
        }
    }
}
