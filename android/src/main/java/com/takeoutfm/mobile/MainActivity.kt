package com.takeoutfm.mobile

import android.content.Intent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.mobile.ui.theme.isDark
import com.takeoutfm.shared.settings.PlaySettings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.takeoutfm.mobile.ui.App
import com.takeoutfm.mobile.ui.theme.TakeoutTheme
import com.takeoutfm.mobile.voice.VoiceHandler
import com.takeoutfm.shared.api.ResponseCache
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
    @Inject lateinit var voice: VoiceHandler
    @Inject lateinit var settings: PlaySettings

    /** Created here so every request from the start is kept for offline browsing. */
    @Inject lateinit var responseCache: ResponseCache

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val mode by settings.theme.collectAsStateWithLifecycle()
            val dark = isDark(mode)
            // Status and navigation bar icons follow the app's theme, not just the system's.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            TakeoutTheme(mode) {
                App()
            }
        }
        if (savedInstanceState == null) handleVoice(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoice(intent)
    }

    /** Assistant ("play Beck on TakeoutFM") and the Takeout app actions, once signed in. */
    private fun handleVoice(intent: Intent?) {
        if (session.state.value.isLoggedIn) voice.handle(intent)
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
