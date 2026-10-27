package com.takeoutfm.tv.data.takeout

import android.content.Context
import android.os.Build
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.db.AndroidDatabase
import com.takeoutfm.shared.db.TakeoutDb
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.listen.ListenBrainzAccount
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.store.AndroidStores
import com.takeoutfm.shared.video.TakeoutVideoRepository
import com.takeoutfm.tv.BuildConfig
import com.takeoutfm.tv.playback.MusicService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the shared Takeout core to the TV app. The preferences files are the ones the app
 * used before the core moved to `:shared`, so saved sign-ins and settings carry over.
 */
@Module
@InstallIn(SingletonComponent::class)
object TakeoutModule {
    @Provides
    @Singleton
    fun session(@ApplicationContext context: Context): TakeoutSession =
        TakeoutSession(AndroidStores.encrypted(context, "takeout_session"))

    @Provides
    @Singleton
    fun api(session: TakeoutSession): TakeoutApi =
        TakeoutApi(session, "TakeoutFM-TV/1.0 (takeoutfm.com; Android ${Build.VERSION.RELEASE})")

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
    fun music(api: TakeoutApi, library: TakeoutLibrary, urls: TakeoutUrls): TakeoutMusicRepository =
        TakeoutMusicRepository(api, library, urls)

    /** The shared database, after moving in anything older builds kept in preferences. */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TakeoutDb =
        AndroidDatabase.open(context).also { LegacyImport.run(context, it) }

    @Provides
    @Singleton
    fun progress(api: TakeoutApi, session: TakeoutSession, db: TakeoutDb): TakeoutProgress =
        TakeoutProgress(api, session, db)

    @Provides
    @Singleton
    fun podcasts(api: TakeoutApi, urls: TakeoutUrls, progress: TakeoutProgress): TakeoutPodcastRepository =
        TakeoutPodcastRepository(api, urls, progress)

    @Provides
    @Singleton
    fun history(session: TakeoutSession, db: TakeoutDb): MusicHistory = MusicHistory(session, db)

    @Provides
    @Singleton
    fun listenReporter(
        api: TakeoutApi,
        listenBrainz: ListenBrainzAccount,
        db: TakeoutDb,
        settings: TakeoutSettings,
    ): ListenReporter = ListenReporter(
        api, listenBrainz, db,
        reportPlays = { settings.reportPlays.value },
        submitListens = { settings.submitListens.value },
    )

    @Provides
    @Singleton
    fun playerFactory(
        @ApplicationContext context: Context,
        session: TakeoutSession,
    ): TakeoutPlayerFactory = TakeoutPlayerFactory(context, session, BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun musicPlayer(
        @ApplicationContext context: Context,
        session: TakeoutSession,
        history: MusicHistory,
    ): MusicPlayer = MusicPlayer(context, session, history, MusicService::class.java)

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
    fun listenBrainz(@ApplicationContext context: Context): ListenBrainzAccount =
        ListenBrainzAccount(AndroidStores.encrypted(context, "listenbrainz"))
}
