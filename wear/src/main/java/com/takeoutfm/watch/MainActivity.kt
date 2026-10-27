package com.takeoutfm.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.takeoutfm.shared.api.ResponseCache
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.watch.playback.StreamingNetwork
import com.takeoutfm.watch.ui.WatchApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: TakeoutSession
    @Inject lateinit var library: TakeoutLibrary
    @Inject lateinit var progress: TakeoutProgress
    @Inject lateinit var listenReporter: ListenReporter
    @Inject lateinit var streamingNetwork: StreamingNetwork

    /** Created here so every request is kept for browsing offline. */
    @Inject lateinit var responseCache: ResponseCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchApp() }
    }

    /** Back on the wrist: refresh the library and resend anything kept offline. */
    override fun onStart() {
        super.onStart()
        // Browsing is likely to end in playing: bring up the fast network now.
        streamingNetwork.hold(Screen)
        if (!session.state.value.isLoggedIn) return
        lifecycleScope.launch { library.refresh(maxAgeMs = 60_000) }
        lifecycleScope.launch { progress.sync() }
        listenReporter.retry()
    }

    override fun onStop() {
        streamingNetwork.release(Screen)
        super.onStop()
    }

    private companion object {
        const val Screen = "screen"
    }
}
