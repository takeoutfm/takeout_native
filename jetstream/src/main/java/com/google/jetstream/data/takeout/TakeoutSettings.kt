package com.google.jetstream.data.takeout

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-device app preferences (not synced to the server). */
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

    companion object {
        const val AllTime = 0
        const val DefaultContinueWatchingDays = 30
        val ContinueWatchingDayOptions = listOf(7, 14, 30, 90, AllTime)

        private const val KEY_CONTINUE_WATCHING_DAYS = "continue_watching_days"
        private const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
    }
}
