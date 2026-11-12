package com.takeoutfm.shared.linux

import com.takeoutfm.shared.api.DbResponseCache
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.api.httpEngine
import com.takeoutfm.shared.db.LinuxDatabase
import com.takeoutfm.shared.guide.GuideLoader
import com.takeoutfm.shared.guide.LiveGuide
import com.takeoutfm.shared.guide.TakeoutGuideRepository
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.listen.ListenBrainzAccount
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.settings.PlaySettings
import com.takeoutfm.shared.store.FileStore
import com.takeoutfm.shared.store.SecretStore
import com.takeoutfm.shared.video.TakeoutVideoRepository
import io.ktor.client.engine.HttpClientEngine

/**
 * The shared core for the Linux app, as the Mac's TakeoutCore: one of each, created once by
 * `takeout_init`. [secrets] keeps the session and ListenBrainz tokens (the app's Secret
 * Service store); everything else saved goes to JSON files and the database in [dataDirectory]
 * (`$XDG_DATA_HOME/takeoutfm`, which must exist).
 */
class LinuxCore internal constructor(
    secrets: SecretStore,
    userAgent: String,
    dataDirectory: String,
    engine: HttpClientEngine,
) {
    constructor(secrets: SecretStore, userAgent: String, dataDirectory: String) :
        this(secrets, userAgent, dataDirectory, httpEngine().create())

    val session = TakeoutSession(secrets.named("takeout_session"))
    val api = TakeoutApi(session, userAgent, engine)
    val urls = TakeoutUrls(session)
    val db = LinuxDatabase.open(dataDirectory)
    val library = TakeoutLibrary(api, session, FileStore(dataDirectory, "takeout_library"))
    val settings = PlaySettings(FileStore(dataDirectory, "play_settings"))
    val progress = TakeoutProgress(api, session, db)
    val history = MusicHistory(session, db)
    val streamHistory = StreamHistory(db)
    val listenBrainz = ListenBrainzAccount(secrets.named("listenbrainz"))
    val listenReporter = ListenReporter(
        api, listenBrainz, db,
        reportPlays = { settings.reportPlays.value },
        submitListens = { settings.submitListens.value },
    )
    val music = TakeoutMusicRepository(api, library, urls)
    val podcasts = TakeoutPodcastRepository(api, urls, progress)
    val video = TakeoutVideoRepository(api, session, library, urls, progress)
    val downloads = MediaDownloads(dataDirectory, session, userAgent, engine)
    val guide = TakeoutGuideRepository(api, urls)

    /** The guide from now on, for the live player: reloaded when it gets old or runs short. */
    val liveGuide = LiveGuide(guide)

    /** The guide page's guide: days ahead, fetched in windows as it scrolls. */
    val guideLoader = GuideLoader(guide)

    init {
        // Every GET is kept for browsing offline, as on Android.
        api.responseCache = DbResponseCache(db, session)
    }
}
