package com.takeoutfm.watch.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.takeoutfm.shared.settings.PlaySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * A fast network while it's needed. A watch near its phone routes everything over Bluetooth
 * through the phone, which is slow to start songs, and only brings up Wi‑Fi (or its own mobile
 * data) for apps that ask. While anything [hold]s it (the app on screen, playback), this asks
 * for Wi‑Fi — or mobile data too, with Stream over mobile data — and binds the process to it,
 * so the API and streams use it; [ReleaseDelayMs] after the last [release] it lets it go.
 * Main thread only.
 */
@Singleton
class StreamingNetwork @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: PlaySettings,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val holders = mutableSetOf<String>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        // A changed setting applies to the request already made.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            settings.streamOverMobileData.drop(1).collect {
                if (callback != null) {
                    stop()
                    start()
                }
            }
        }
    }

    fun hold(reason: String) {
        holders += reason
        handler.removeCallbacks(releaseNow)
        if (callback == null) start()
    }

    fun release(reason: String) {
        if (holders.remove(reason) && holders.isEmpty()) {
            handler.removeCallbacks(releaseNow)
            handler.postDelayed(releaseNow, ReleaseDelayMs)
        }
    }

    private val releaseNow = Runnable { if (holders.isEmpty()) stop() }

    private fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Only a network the system has checked reaches the internet: a Wi‑Fi that's
            // connected but blocked (a router pausing the watch failed every request) is
            // left alone, and the app stays on the phone's Bluetooth link.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .apply { if (settings.streamOverMobileData.value) addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR) }
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(Tag, "using $network")
                connectivity.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                if (connectivity.boundNetworkForProcess == network) connectivity.bindProcessToNetwork(null)
            }
        }
        try {
            connectivity.requestNetwork(request, cb)
            callback = cb
        } catch (e: RuntimeException) {
            // Too many requests or not allowed: carry on with the default network.
            Log.w(Tag, "request failed", e)
        }
    }

    private fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity.unregisterNetworkCallback(cb) }
        connectivity.bindProcessToNetwork(null)
        Log.i(Tag, "released")
    }

    private companion object {
        const val Tag = "StreamingNetwork"
        const val ReleaseDelayMs = 30_000L
    }
}
