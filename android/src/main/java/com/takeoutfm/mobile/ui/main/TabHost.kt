package com.takeoutfm.mobile.ui.main

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.takeoutfm.mobile.ui.home.HomeScreen
import com.takeoutfm.mobile.ui.home.HomeViewModel
import com.takeoutfm.mobile.ui.music.ArtistsScreen
import com.takeoutfm.mobile.ui.music.PlayerScreen
import com.takeoutfm.mobile.ui.music.RadioScreen
import com.takeoutfm.mobile.ui.music.RecentScreen
import com.takeoutfm.mobile.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

// Routes. Each tab has its own NavHost (and so its own back stack); pages any tab can open
// are added to every tab's graph as they arrive.
@Serializable object HomeRoute
@Serializable object ArtistsRoute
@Serializable object RecentRoute
@Serializable object RadioRoute
@Serializable object PlayerRoute
@Serializable object SettingsRoute

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
    NavHost(navController, startDestination = start) {
        composable<HomeRoute> {
            HomeScreen(homeViewModel, onSettings = { navController.navigate(SettingsRoute) })
        }
        composable<ArtistsRoute> { ArtistsScreen(mainViewModel) }
        composable<RecentRoute> { RecentScreen() }
        composable<RadioRoute> { RadioScreen() }
        composable<PlayerRoute> { PlayerScreen(mainViewModel) }
        composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
