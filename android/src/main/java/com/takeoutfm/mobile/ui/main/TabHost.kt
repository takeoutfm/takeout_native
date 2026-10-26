package com.takeoutfm.mobile.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.takeoutfm.mobile.ui.home.HomeScreen
import com.takeoutfm.mobile.ui.home.HomeViewModel
import com.takeoutfm.mobile.ui.music.ActivityScreen
import com.takeoutfm.mobile.ui.music.AlbumScreen
import com.takeoutfm.mobile.ui.music.RecentTracksScreen
import com.takeoutfm.mobile.ui.music.ArtistScreen
import com.takeoutfm.mobile.ui.music.DownloadsScreen
import com.takeoutfm.mobile.ui.music.ArtistsScreen
import com.takeoutfm.mobile.ui.music.MusicNav
import com.takeoutfm.mobile.ui.music.PlayerScreen
import com.takeoutfm.mobile.ui.music.PlaylistsScreen
import com.takeoutfm.mobile.ui.music.PodcastScreen
import com.takeoutfm.mobile.ui.music.QueueScreen
import com.takeoutfm.mobile.ui.music.RadioScreen
import com.takeoutfm.mobile.ui.music.RecentScreen
import com.takeoutfm.mobile.ui.music.SearchScreen
import com.takeoutfm.mobile.ui.settings.LinkScreen
import com.takeoutfm.mobile.ui.settings.SettingsScreen
import com.takeoutfm.mobile.ui.video.GenreScreen
import com.takeoutfm.mobile.ui.video.PersonScreen
import com.takeoutfm.mobile.ui.video.VideoNav
import com.takeoutfm.mobile.ui.video.VideoScreen
import kotlinx.serialization.Serializable

// Routes. Each tab has its own NavHost (and so its own back stack); the pages any tab can
// open (artists, albums, search, playlists) are in every tab's graph.
@Serializable object HomeRoute
@Serializable object ArtistsRoute
@Serializable object RecentRoute
@Serializable object RadioRoute
@Serializable object PlayerRoute
@Serializable object SettingsRoute
@Serializable object LinkRoute
@Serializable object SearchRoute
@Serializable object PlaylistsRoute
@Serializable object RecentTracksRoute
@Serializable object ActivityRoute
@Serializable object DownloadsRoute
@Serializable data class ArtistRoute(val id: String)
@Serializable data class AlbumRoute(val id: String)
@Serializable data class VideoRoute(val id: String)
@Serializable data class PodcastRoute(val id: String)
@Serializable data class PersonRoute(val id: String)
@Serializable data class GenreRoute(val id: String)
@Serializable data class QueueRoute(val kind: String, val id: String, val title: String)

@Composable
fun TabHost(
    tab: Tab,
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    mainViewModel: MainViewModel,
) {
    val start: Any = when (tab) {
        Tab.Home -> HomeRoute
        Tab.Artists -> ArtistsRoute
        Tab.Recent -> RecentRoute
        Tab.Radio -> RadioRoute
        Tab.Player -> PlayerRoute
    }
    val nav = remember(navController) {
        MusicNav(
            openArtist = { navController.navigate(ArtistRoute(it)) },
            openAlbum = { navController.navigate(AlbumRoute(it)) },
            openQueue = { kind, id, title -> navController.navigate(QueueRoute(kind, id, title)) },
            openPlaylists = { navController.navigate(PlaylistsRoute) },
            openRecentTracks = { navController.navigate(RecentTracksRoute) },
            openActivity = { navController.navigate(ActivityRoute) },
            openPodcast = { navController.navigate(PodcastRoute(it)) },
            openDownloads = { navController.navigate(DownloadsRoute) },
            openSearch = { navController.navigate(SearchRoute) },
            back = { navController.popBackStack() },
        )
    }
    val videoNav = remember(navController) {
        VideoNav(
            openVideo = { navController.navigate(VideoRoute(it)) },
            openPerson = { navController.navigate(PersonRoute(it)) },
            openGenre = { navController.navigate(GenreRoute(it)) },
            back = { navController.popBackStack() },
        )
    }
    NavHost(navController, startDestination = start) {
        composable<HomeRoute> {
            HomeScreen(
                homeViewModel, mainViewModel, nav, videoNav,
                onSettings = { navController.navigate(SettingsRoute) },
                onLink = { navController.navigate(LinkRoute) },
            )
        }
        composable<ArtistsRoute> { ArtistsScreen(mainViewModel, nav) }
        composable<RecentRoute> { RecentScreen(mainViewModel) }
        composable<RadioRoute> { RadioScreen(mainViewModel) }
        composable<PlayerRoute> { PlayerScreen(mainViewModel) }
        composable<SettingsRoute> { SettingsScreen(onBack = nav.back) }
        composable<LinkRoute> { LinkScreen(onBack = nav.back) }
        musicPages(mainViewModel, nav, videoNav)
        composable<VideoRoute> { VideoScreen(videoNav) }
        composable<PersonRoute> { PersonScreen(videoNav) }
        composable<GenreRoute> { GenreScreen(videoNav) }
    }
}

private fun NavGraphBuilder.musicPages(main: MainViewModel, nav: MusicNav, videoNav: VideoNav) {
    composable<ArtistRoute> { ArtistScreen(main, nav) }
    composable<AlbumRoute> { AlbumScreen(main, nav) }
    composable<SearchRoute> { SearchScreen(main, nav, videoNav) }
    composable<PlaylistsRoute> { PlaylistsScreen(nav) }
    composable<QueueRoute> { QueueScreen(main, nav) }
    composable<RecentTracksRoute> { RecentTracksScreen(main, nav) }
    composable<PodcastRoute> { PodcastScreen(main, nav) }
    composable<ActivityRoute> { ActivityScreen(main, nav) }
    composable<DownloadsRoute> { DownloadsScreen(main, nav) }
}
