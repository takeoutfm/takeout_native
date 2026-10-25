package com.takeoutfm.tv.data.takeout

import android.content.Context
import com.takeoutfm.shared.progress.ProgressRules
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-device app preferences (not synced to the server). The ListenBrainz token is kept apart,
 * encrypted, in [com.takeoutfm.shared.listen.ListenBrainzAccount].
 */
@Singleton
class TakeoutSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("takeout_settings", Context.MODE_PRIVATE)

    private val _continueWatchingDays =
        MutableStateFlow(prefs.getInt(KEY_CONTINUE_WATCHING_DAYS, DefaultContinueWatchingDays))

    /** How far back Continue Watching looks, in days; [AllTime] for no limit. */
    val continueWatchingDays: StateFlow<Int> = _continueWatchingDays.asStateFlow()

    fun setContinueWatchingDays(days: Int) {
        _continueWatchingDays.value = days
        prefs.edit().putInt(KEY_CONTINUE_WATCHING_DAYS, days).apply()
    }

    /**
     * The last subtitle choice: null = never chosen (player default), "" = off, otherwise the
     * preferred language tag (e.g. "en").
     */
    val subtitleLanguage: String? get() = prefs.getString(KEY_SUBTITLE_LANGUAGE, null)

    fun setSubtitleLanguage(language: String) {
        prefs.edit().putString(KEY_SUBTITLE_LANGUAGE, language).apply()
    }

    /** Language of the last audio track picked in the player (e.g. "en"); null if never picked. */
    val audioLanguage: String? get() = prefs.getString(KEY_AUDIO_LANGUAGE, null)

    fun setAudioLanguage(language: String) {
        prefs.edit().putString(KEY_AUDIO_LANGUAGE, language).apply()
    }

    private val _reportPlays = MutableStateFlow(prefs.getBoolean(KEY_REPORT_PLAYS, false))

    /** Report music plays to the Takeout server (its activity / listening stats). */
    val reportPlays: StateFlow<Boolean> = _reportPlays.asStateFlow()

    fun setReportPlays(on: Boolean) {
        _reportPlays.value = on
        prefs.edit().putBoolean(KEY_REPORT_PLAYS, on).apply()
    }

    private val _submitListens = MutableStateFlow(prefs.getBoolean(KEY_SUBMIT_LISTENS, false))

    /** Submit music plays to ListenBrainz (needs a connected account, [ListenBrainzAccount]). */
    val submitListens: StateFlow<Boolean> = _submitListens.asStateFlow()

    fun setSubmitListens(on: Boolean) {
        _submitListens.value = on
        prefs.edit().putBoolean(KEY_SUBMIT_LISTENS, on).apply()
    }

    private val _mode = MutableStateFlow(
        LibraryMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) }
            ?: LibraryMode.Video
    )

    /** Video or music: which set of tabs the app shows; the app starts in the last one used. */
    val mode: StateFlow<LibraryMode> = _mode.asStateFlow()

    fun setMode(mode: LibraryMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        const val AllTime = ProgressRules.AllTime
        const val DefaultContinueWatchingDays = 30
        val ContinueWatchingDayOptions = listOf(7, 14, 30, 90, AllTime)

        private const val KEY_CONTINUE_WATCHING_DAYS = "continue_watching_days"
        private const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        private const val KEY_AUDIO_LANGUAGE = "audio_language"
        private const val KEY_MODE = "library_mode"
        private const val KEY_REPORT_PLAYS = "report_plays"
        private const val KEY_SUBMIT_LISTENS = "submit_listens"
    }
}

/** The two halves of the app, switched with the TAKEOUT logo in the top bar. */
enum class LibraryMode { Video, Music }
