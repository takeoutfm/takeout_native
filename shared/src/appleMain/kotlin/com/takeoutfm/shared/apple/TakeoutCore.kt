package com.takeoutfm.shared.apple

import com.takeoutfm.shared.api.DbResponseCache
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.db.AppleDatabase
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.listen.ListenBrainzAccount
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.settings.PlaySettings
import com.takeoutfm.shared.store.KeyValueStore
import com.takeoutfm.shared.store.UserDefaultsStore
import com.takeoutfm.shared.video.TakeoutVideoRepository

/**
 * The shared core for the Apple apps, wired as the Android apps' Hilt modules do: one of each,
 * created once at launch. [secureStore] keeps the session and ListenBrainz tokens (the Swift
 * app's Keychain store); everything else saved goes to the user defaults, and the database to
 * [dataDirectory] (the app's Application Support folder).
 */
class TakeoutCore(secureStore: KeyValueStore, userAgent: String, dataDirectory: String) {
    val session = TakeoutSession(secureStore)
    val api = TakeoutApi(session, userAgent)
    val urls = TakeoutUrls(session)
    val db = AppleDatabase.open(dataDirectory)
    val library = TakeoutLibrary(api, session, UserDefaultsStore("takeout_library"))
    val settings = PlaySettings(UserDefaultsStore("play_settings"))
    val progress = TakeoutProgress(api, session, db)
    val history = MusicHistory(session, db)
    val streamHistory = StreamHistory(db)
    val listenBrainz = ListenBrainzAccount(secureStore)
    val listenReporter = ListenReporter(
        api, listenBrainz, db,
        reportPlays = { settings.reportPlays.value },
        submitListens = { settings.submitListens.value },
    )
    val music = TakeoutMusicRepository(api, library, urls)
    val podcasts = TakeoutPodcastRepository(api, urls, progress)
    val video = TakeoutVideoRepository(api, session, library, urls, progress)

    init {
        // Every GET is kept for browsing offline, as on Android.
        api.responseCache = DbResponseCache(db, session)
    }
}
