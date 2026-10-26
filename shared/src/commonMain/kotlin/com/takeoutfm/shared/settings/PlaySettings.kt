package com.takeoutfm.shared.settings

import com.takeoutfm.shared.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's colour theme. */
enum class ThemeMode { System, Light, Dark }

/**
 * Per-device settings for the phone and watch apps (the TV keeps its own), with the
 * Flutter app's defaults.
 */
class PlaySettings(private val store: KeyValueStore) {
    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(store.getString(key)?.toBooleanStrictOrNull() ?: default)

    private val _reportPlays = flag(KEY_REPORT_PLAYS, true)

    /** Track Activity: add what's played here to the Takeout server's listening history. */
    val reportPlays: StateFlow<Boolean> = _reportPlays.asStateFlow()

    fun setReportPlays(on: Boolean) = set(_reportPlays, KEY_REPORT_PLAYS, on)

    private val _submitListens = flag(KEY_SUBMIT_LISTENS, true)

    /** Submit what's played here to ListenBrainz (once an account is connected). */
    val submitListens: StateFlow<Boolean> = _submitListens.asStateFlow()

    fun setSubmitListens(on: Boolean) = set(_submitListens, KEY_SUBMIT_LISTENS, on)

    private val _playbackCaching = flag(KEY_PLAYBACK_CACHING, false)

    /** Playback Caching: keep tracks as they're streamed so later plays are local. */
    val playbackCaching: StateFlow<Boolean> = _playbackCaching.asStateFlow()

    fun setPlaybackCaching(on: Boolean) = set(_playbackCaching, KEY_PLAYBACK_CACHING, on)

    private val _theme = MutableStateFlow(
        store.getString(KEY_THEME)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System
    )

    /** Light, dark, or following the system. */
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        store.putString(KEY_THEME, mode.name)
    }

    private fun set(flow: MutableStateFlow<Boolean>, key: String, on: Boolean) {
        flow.value = on
        store.putString(key, on.toString())
    }

    private companion object {
        const val KEY_REPORT_PLAYS = "reportPlays"
        const val KEY_SUBMIT_LISTENS = "submitListens"
        const val KEY_PLAYBACK_CACHING = "playbackCaching"
        const val KEY_THEME = "theme"
    }
}
