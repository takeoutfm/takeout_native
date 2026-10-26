package com.takeoutfm.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.takeoutfm.mobile.ui.App
import com.takeoutfm.mobile.ui.theme.TakeoutTheme
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.progress.TakeoutProgress
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: TakeoutSession
    @Inject lateinit var library: TakeoutLibrary
    @Inject lateinit var progress: TakeoutProgress
    @Inject lateinit var listenReporter: ListenReporter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TakeoutTheme {
                App()
            }
        }
    }

    /** Back in the foreground: refresh the library index and resend anything kept offline. */
    override fun onStart() {
        super.onStart()
        if (!session.state.value.isLoggedIn) return
        lifecycleScope.launch { library.refresh(maxAgeMs = RefreshAfterMs) }
        lifecycleScope.launch { progress.sync() }
        listenReporter.retry()
    }

    private companion object {
        const val RefreshAfterMs = 60_000L
    }
}
