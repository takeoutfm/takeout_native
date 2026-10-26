package com.takeoutfm.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.mobile.ui.main.MainScaffold
import com.takeoutfm.mobile.ui.signin.SignInScreen
import com.takeoutfm.shared.api.SessionState
import com.takeoutfm.shared.api.TakeoutSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SessionViewModel @Inject constructor(session: TakeoutSession) : ViewModel() {
    val state: StateFlow<SessionState> = session.state
}

/** Sign-in until there's a session, then the app; signing out returns to sign-in. */
@Composable
fun App(viewModel: SessionViewModel = hiltViewModel()) {
    val session by viewModel.state.collectAsStateWithLifecycle()
    if (session.isLoggedIn) MainScaffold() else SignInScreen()
}
