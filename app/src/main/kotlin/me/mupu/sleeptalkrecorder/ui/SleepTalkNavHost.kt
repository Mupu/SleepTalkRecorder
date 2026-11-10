package me.mupu.sleeptalkrecorder.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.mupu.sleeptalkrecorder.AppContainer
import me.mupu.sleeptalkrecorder.playback.SleepPlayer
import me.mupu.sleeptalkrecorder.settings.AppLanguages
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
import me.mupu.sleeptalkrecorder.ui.whatsnew.WhatsNewDialog
import me.mupu.sleeptalkrecorder.ui.whatsnew.WhatsNewScreen
import me.mupu.sleeptalkrecorder.ui.whatsnew.WhatsNewViewModel

/** The app's screens. Home is the start; the others are opened from it and go back to it. */
private object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val WHATS_NEW = "whats_new"
    const val SLEEP_ID = "sleepId"
    const val EVENT_ID = "eventId"
    const val PLAY = "play"
    const val SLEEP = "sleep/{$SLEEP_ID}?$EVENT_ID={$EVENT_ID}&$PLAY={$PLAY}"
    const val STATS = "stats?$SLEEP_ID={$SLEEP_ID}"

    /** No sleep: the stats screen opened from the library counts all sleeps. */
    const val NO_SLEEP = -1L

    /** No event: the sleep screen opens at the start of the sleep. */
    const val NO_EVENT = -1L

    /** The sleep [id], opened at the start or, given [eventId], at that event, which with [play] plays on its own. */
    fun sleep(id: Long, eventId: Long? = null, play: Boolean = false) =
        if (eventId == null) "sleep/$id" else "sleep/$id?$EVENT_ID=$eventId&$PLAY=$play"

    fun stats(sleepId: Long? = null) = if (sleepId == null) "stats" else "stats?$SLEEP_ID=$sleepId"
}

/**
 * Navigation between home, library, settings, the sleep screen, stats, the permissions page and the
 * list of all updates, with the pop-up of an update's news over whichever screen is open.
 */
@Composable
fun SleepTalkNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val whatsNew = viewModel { WhatsNewViewModel(container.changelogs, container.whatsNew, container.installedVersion) }
    val language = AppLanguages.shown(LocalConfiguration.current)
    LaunchedEffect(language) { whatsNew.showIn(language) }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel { HomeViewModel(container.recorder, container.sleepRepository, container.readiness, container.updater) },
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
                onOpenFavourite = { sleepId, eventId, play -> navController.navigate(Routes.sleep(sleepId, eventId, play)) },
                onOpenStats = { navController.navigate(Routes.stats()) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container.settings, container.modelLibrary, container.updater, container.analysisExport) },
                onBack = { navController.popBackStack() },
                onOpenWhatsNew = { navController.navigate(Routes.WHATS_NEW) },
            )
        }
        composable(Routes.WHATS_NEW) {
            WhatsNewScreen(viewModel = whatsNew, onBack = { navController.popBackStack() })
        }
        composable(
            Routes.SLEEP,
            arguments = listOf(
                navArgument(Routes.SLEEP_ID) { type = NavType.LongType },
                navArgument(Routes.EVENT_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_EVENT
                },
                navArgument(Routes.PLAY) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val sleepId = entry.arguments?.getLong(Routes.SLEEP_ID) ?: return@composable
            val eventId = entry.arguments?.getLong(Routes.EVENT_ID)?.takeIf { it != Routes.NO_EVENT }
            val play = entry.arguments?.getBoolean(Routes.PLAY) ?: false
            val context = LocalContext.current.applicationContext
            SleepScreen(
                viewModel = viewModel {
                    SleepViewModel(sleepId, container.sleepRepository, container.settings, SleepPlayer(context, container::playbackChanged), container.transcriptions, openEventId = eventId, playOpenEvent = play)
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

    val news by whatsNew.news.collectAsStateWithLifecycle()
    if (news.isNotEmpty()) {
        WhatsNewDialog(news, onDismiss = whatsNew::dismissNews, onOpenAll = { navController.navigate(Routes.WHATS_NEW) })
    }
}
