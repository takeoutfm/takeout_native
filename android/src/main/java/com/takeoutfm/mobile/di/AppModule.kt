package com.takeoutfm.mobile.di

import android.content.Context
import android.os.Build
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.mobile.BuildConfig
import com.takeoutfm.mobile.playback.MusicService
import com.takeoutfm.shared.api.DbResponseCache
import com.takeoutfm.shared.api.ResponseCache
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.db.AndroidDatabase
import com.takeoutfm.shared.db.TakeoutDb
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.listen.ListenBrainzAccount
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.settings.PlaySettings
import com.takeoutfm.shared.store.AndroidStores
import com.takeoutfm.shared.video.TakeoutVideoRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The shared Takeout core and Android playback, as app singletons. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun session(@ApplicationContext context: Context): TakeoutSession =
        TakeoutSession(AndroidStores.encrypted(context, "takeout_session"))

    @Provides
    @Singleton
    fun api(session: TakeoutSession): TakeoutApi = TakeoutApi(
        session,
        "TakeoutFM-Android/${BuildConfig.VERSION_NAME} (takeoutfm.com; Android ${Build.VERSION.RELEASE})",
    )

    @Provides
    @Singleton
    fun urls(session: TakeoutSession): TakeoutUrls = TakeoutUrls(session)

    @Provides
    @Singleton
    fun library(
        @ApplicationContext context: Context,
        api: TakeoutApi,
        session: TakeoutSession,
    ): TakeoutLibrary = TakeoutLibrary(api, session, AndroidStores.plain(context, "takeout_library"))

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TakeoutDb = AndroidDatabase.open(context)

    /** Kept for browsing offline once the database is available. */
    @Provides
    @Singleton
    fun responseCache(db: TakeoutDb, session: TakeoutSession, api: TakeoutApi): ResponseCache =
        DbResponseCache(db, session).also { api.responseCache = it }

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): PlaySettings =
        PlaySettings(AndroidStores.plain(context, "play_settings"))

    @Provides
    @Singleton
    fun progress(api: TakeoutApi, session: TakeoutSession, db: TakeoutDb): TakeoutProgress =
        TakeoutProgress(api, session, db)

    @Provides
    @Singleton
    fun history(session: TakeoutSession, db: TakeoutDb): MusicHistory = MusicHistory(session, db)

    @Provides
    @Singleton
    fun streamHistory(db: TakeoutDb): StreamHistory = StreamHistory(db)

    @Provides
    @Singleton
    fun listenBrainz(@ApplicationContext context: Context): ListenBrainzAccount =
        ListenBrainzAccount(AndroidStores.encrypted(context, "listenbrainz"))

    @Provides
    @Singleton
    fun listenReporter(
        api: TakeoutApi,
        listenBrainz: ListenBrainzAccount,
        db: TakeoutDb,
        settings: PlaySettings,
    ): ListenReporter = ListenReporter(
        api, listenBrainz, db,
        reportPlays = { settings.reportPlays.value },
        submitListens = { settings.submitListens.value },
    )

    @Provides
    @Singleton
    fun music(api: TakeoutApi, library: TakeoutLibrary, urls: TakeoutUrls): TakeoutMusicRepository =
        TakeoutMusicRepository(api, library, urls)

    @Provides
    @Singleton
    fun podcasts(api: TakeoutApi, urls: TakeoutUrls, progress: TakeoutProgress): TakeoutPodcastRepository =
        TakeoutPodcastRepository(api, urls, progress)

    @Provides
    @Singleton
    fun video(
        api: TakeoutApi,
        session: TakeoutSession,
        library: TakeoutLibrary,
        urls: TakeoutUrls,
        progress: TakeoutProgress,
    ): TakeoutVideoRepository = TakeoutVideoRepository(api, session, library, urls, progress)

    @Provides
    @Singleton
    fun playerFactory(
        @ApplicationContext context: Context,
        session: TakeoutSession,
    ): TakeoutPlayerFactory = TakeoutPlayerFactory(context, session, BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun mediaCache(
        @ApplicationContext context: Context,
        playerFactory: TakeoutPlayerFactory,
        settings: PlaySettings,
    ): TakeoutMediaCache = TakeoutMediaCache(context, playerFactory.upstream) { settings.playbackCaching.value }

    @Provides
    @Singleton
    fun musicPlayer(
        @ApplicationContext context: Context,
        session: TakeoutSession,
        history: MusicHistory,
    ): MusicPlayer = MusicPlayer(context, session, history, MusicService::class.java)
}
