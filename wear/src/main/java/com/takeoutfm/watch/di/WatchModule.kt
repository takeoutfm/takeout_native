package com.takeoutfm.watch.di

import android.content.Context
import android.os.Build
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.media.offline.TakeoutMediaCache
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
import com.takeoutfm.watch.BuildConfig
import com.takeoutfm.watch.playback.MusicService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The shared Takeout core and Android playback for the watch (as the phone's AppModule). */
@Module
@InstallIn(SingletonComponent::class)
object WatchModule {
    @Provides @Singleton
    fun session(@ApplicationContext context: Context) =
        TakeoutSession(AndroidStores.encrypted(context, "takeout_session"))

    @Provides @Singleton
    fun api(session: TakeoutSession) = TakeoutApi(
        session,
        "TakeoutFM-Wear/${BuildConfig.VERSION_NAME} (takeoutfm.com; Android ${Build.VERSION.RELEASE})",
    )

    @Provides @Singleton
    fun urls(session: TakeoutSession) = TakeoutUrls(session)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): TakeoutDb = AndroidDatabase.open(context)

    @Provides @Singleton
    fun responseCache(db: TakeoutDb, session: TakeoutSession, api: TakeoutApi): ResponseCache =
        DbResponseCache(db, session).also { api.responseCache = it }

    @Provides @Singleton
    fun library(@ApplicationContext context: Context, api: TakeoutApi, session: TakeoutSession) =
        TakeoutLibrary(api, session, AndroidStores.plain(context, "takeout_library"))

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context) = PlaySettings(AndroidStores.plain(context, "play_settings"))

    @Provides @Singleton
    fun progress(api: TakeoutApi, session: TakeoutSession, db: TakeoutDb) = TakeoutProgress(api, session, db)

    @Provides @Singleton
    fun history(session: TakeoutSession, db: TakeoutDb) = MusicHistory(session, db)

    @Provides @Singleton
    fun streamHistory(db: TakeoutDb) = StreamHistory(db)

    @Provides @Singleton
    fun listenBrainz(@ApplicationContext context: Context) =
        ListenBrainzAccount(AndroidStores.encrypted(context, "listenbrainz"))

    @Provides @Singleton
    fun listenReporter(api: TakeoutApi, listenBrainz: ListenBrainzAccount, db: TakeoutDb, settings: PlaySettings) =
        ListenReporter(
            api, listenBrainz, db,
            reportPlays = { settings.reportPlays.value },
            submitListens = { settings.submitListens.value },
        )

    @Provides @Singleton
    fun music(api: TakeoutApi, library: TakeoutLibrary, urls: TakeoutUrls) = TakeoutMusicRepository(api, library, urls)

    @Provides @Singleton
    fun podcasts(api: TakeoutApi, urls: TakeoutUrls, progress: TakeoutProgress) = TakeoutPodcastRepository(api, urls, progress)

    @Provides @Singleton
    fun playerFactory(@ApplicationContext context: Context, session: TakeoutSession) =
        TakeoutPlayerFactory(context, session, BuildConfig.DEBUG)

    @Provides @Singleton
    fun mediaCache(@ApplicationContext context: Context, playerFactory: TakeoutPlayerFactory, settings: PlaySettings) =
        TakeoutMediaCache(context, playerFactory.upstream) { settings.playbackCaching.value }

    @Provides @Singleton
    fun musicPlayer(@ApplicationContext context: Context, session: TakeoutSession, history: MusicHistory) =
        MusicPlayer(context, session, history, MusicService::class.java)
}
