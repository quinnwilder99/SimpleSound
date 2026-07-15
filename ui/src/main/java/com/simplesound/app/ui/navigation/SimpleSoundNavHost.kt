package com.simplesound.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.HomeScreen
import com.simplesound.app.ui.screens.nowplaying.NowPlayingScreen
import com.simplesound.app.ui.screens.playlistdetail.PlaylistDetailScreen
import com.simplesound.app.ui.screens.search.SearchScreen
import com.simplesound.app.ui.screens.settings.AccentColorScreen
import com.simplesound.app.ui.screens.settings.ManageTabsScreen
import com.simplesound.app.ui.screens.settings.SettingsScreen
import com.simplesound.app.ui.screens.settings.SleepTimerScreen

/** Top-level navigation destinations. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val MANAGE_TABS = "manage_tabs"
    const val ACCENT_COLOR = "accent_color"
    const val SLEEP_TIMER = "sleep_timer"
    const val PLAYLIST = "playlist"
    const val NOW_PLAYING = "now_playing"
    const val SEARCH = "search"
    fun playlist(id: String) = "$PLAYLIST/$id"
}

@Composable
fun SimpleSoundNavHost(
    vm: AppViewModel,
    navController: NavHostController = rememberNavController()
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(vm = vm, navController = navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onManageTabs = { navController.navigate(Routes.MANAGE_TABS) },
                onAccentColor = { navController.navigate(Routes.ACCENT_COLOR) },
                onSleepTimer = { navController.navigate(Routes.SLEEP_TIMER) }
            )
        }
        composable(Routes.MANAGE_TABS) {
            ManageTabsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.ACCENT_COLOR) {
            AccentColorScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.SLEEP_TIMER) {
            SleepTimerScreen(onBack = { navController.popBackStack() })
        }
        composable("${Routes.PLAYLIST}/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            PlaylistDetailScreen(
                vm = vm,
                playlistId = id,
                onBack = { navController.popBackStack() },
                onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) }
            )
        }
        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) }
            )
        }
        }
    }
}
