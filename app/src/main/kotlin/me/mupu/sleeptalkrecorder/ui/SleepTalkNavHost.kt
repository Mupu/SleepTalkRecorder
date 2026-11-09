package me.mupu.sleeptalkrecorder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.mupu.sleeptalkrecorder.AppContainer
import me.mupu.sleeptalkrecorder.playback.SleepPlayer
import me.mupu.sleeptalkrecorder.ui.home.HomeScreen
import me.mupu.sleeptalkrecorder.ui.home.HomeViewModel
import me.mupu.sleeptalkrecorder.ui.library.LibraryScreen
import me.mupu.sleeptalkrecorder.ui.library.LibraryViewModel
import me.mupu.sleeptalkrecorder.ui.settings.SettingsScreen
import me.mupu.sleeptalkrecorder.ui.settings.SettingsViewModel
import me.mupu.sleeptalkrecorder.ui.setup.PermissionsPlace
import me.mupu.sleeptalkrecorder.ui.setup.PermissionsScreen
import me.mupu.sleeptalkrecorder.ui.setup.PermissionsViewModel
import me.mupu.sleeptalkrecorder.ui.sleep.SleepScreen
import me.mupu.sleeptalkrecorder.ui.sleep.SleepViewModel
import me.mupu.sleeptalkrecorder.ui.stats.StatsScreen
import me.mupu.sleeptalkrecorder.ui.stats.StatsViewModel

/** The app's screens. Home is the start; the others are opened from it and go back to it. */
private object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val SLEEP_ID = "sleepId"
    const val SLEEP = "sleep/{$SLEEP_ID}"
    const val STATS = "stats?$SLEEP_ID={$SLEEP_ID}"

    /** No sleep: the stats screen opened from the library counts all sleeps. */
    const val NO_SLEEP = -1L

    fun sleep(id: Long) = "sleep/$id"

    fun stats(sleepId: Long? = null) = if (sleepId == null) "stats" else "stats?$SLEEP_ID=$sleepId"
}

/** Navigation between home, library, settings, the sleep screen, stats and the permissions page. */
@Composable
fun SleepTalkNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel { HomeViewModel(container.recorder, container.sleepRepository, container.readiness) },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSleep = { navController.navigate(Routes.sleep(it)) },
                onFixReadiness = { navController.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                viewModel = viewModel { PermissionsViewModel(container.settings, container.readiness) },
                place = PermissionsPlace.FromHome,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                viewModel = viewModel { LibraryViewModel(container.recorder, container.sleepRepository) },
                onBack = { navController.popBackStack() },
                onOpenRunning = { navController.popBackStack(Routes.HOME, inclusive = false) },
                onOpenSleep = { navController.navigate(Routes.sleep(it)) },
                onOpenStats = { navController.navigate(Routes.stats()) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container.settings, container.modelLibrary) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.SLEEP,
            arguments = listOf(navArgument(Routes.SLEEP_ID) { type = NavType.LongType }),
        ) { entry ->
            val sleepId = entry.arguments?.getLong(Routes.SLEEP_ID) ?: return@composable
            val context = LocalContext.current.applicationContext
            SleepScreen(
                viewModel = viewModel {
                    SleepViewModel(sleepId, container.sleepRepository, container.settings, SleepPlayer(context), container.transcriptions)
                },
                onBack = { navController.popBackStack() },
                onOpenStats = { navController.navigate(Routes.stats(sleepId)) },
            )
        }
        composable(
            Routes.STATS,
            arguments = listOf(
                navArgument(Routes.SLEEP_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_SLEEP
                },
            ),
        ) { entry ->
            val sleepId = entry.arguments?.getLong(Routes.SLEEP_ID)?.takeIf { it != Routes.NO_SLEEP }
            StatsScreen(
                viewModel = viewModel { StatsViewModel(sleepId, container.sleepRepository, container.settings) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
