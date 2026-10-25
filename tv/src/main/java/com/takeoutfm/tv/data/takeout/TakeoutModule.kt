package com.takeoutfm.tv.data.takeout

import android.content.Context
import android.os.Build
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.listen.ListenBrainzAccount
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.store.AndroidStores
import com.takeoutfm.shared.video.TakeoutVideoRepository
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
