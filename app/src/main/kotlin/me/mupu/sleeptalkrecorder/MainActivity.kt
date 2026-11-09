package me.mupu.sleeptalkrecorder

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.settings.ThemeMode
import me.mupu.sleeptalkrecorder.settings.TimeFormat
import me.mupu.sleeptalkrecorder.ui.SleepTalkNavHost
import me.mupu.sleeptalkrecorder.ui.format.ClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.setup.LanguageQuestionScreen
import me.mupu.sleeptalkrecorder.ui.setup.LanguageQuestionViewModel
import me.mupu.sleeptalkrecorder.ui.setup.ModelSetupScreen
import me.mupu.sleeptalkrecorder.ui.setup.ModelSetupViewModel
import me.mupu.sleeptalkrecorder.ui.setup.PermissionsPlace
import me.mupu.sleeptalkrecorder.ui.setup.PermissionsScreen
import me.mupu.sleeptalkrecorder.ui.setup.PermissionsViewModel
import me.mupu.sleeptalkrecorder.ui.setup.SetupStep
import me.mupu.sleeptalkrecorder.ui.theme.SleepTalkRecorderTheme
import me.mupu.sleeptalkrecorder.ui.theme.isDark
import kotlinx.coroutines.flow.combine

/**
 * The app's only activity; every screen is a Compose destination inside it. It is an
 * [AppCompatActivity] because AppCompat applies the per-app language on Android 12.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SleepTalkApplication).container
        setContent {
            val settings by remember { appSettings(container.settings) }.collectAsStateWithLifecycle(null)
            // Until the settings are read, which takes a few milliseconds, the window stays empty
            // instead of showing the wrong theme or screen first.
            val current = settings ?: return@setContent

            val darkTheme = current.theme.isDark()
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { darkTheme },
                )
                onDispose {}
            }

            val clock = remember(current.timeFormat) { ClockFormatter(current.timeFormat) }
            SleepTalkRecorderTheme(darkTheme) {
                CompositionLocalProvider(LocalClockFormatter provides clock) {
                    when (current.setupStep) {
                        null -> SleepTalkNavHost(container)
                        SetupStep.LANGUAGE ->
                            LanguageQuestionScreen(viewModel { LanguageQuestionViewModel(container.settings) })
                        SetupStep.PERMISSIONS -> {
                            val permissions = viewModel { PermissionsViewModel(container.settings, container.readiness) }
                            PermissionsScreen(
                                viewModel = permissions,
                                place = PermissionsPlace.Setup(SetupStep.PERMISSIONS),
                                onBack = permissions::backSetupStep,
                                onDone = permissions::finishSetupStep,
                            )
                        }
                        SetupStep.MODEL ->
                            ModelSetupScreen(viewModel { ModelSetupViewModel(container.settings, container.modelLibrary) })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions and battery settings may have changed outside the app, also in the system
        // dialog or settings page the app just returns from.
        (application as SleepTalkApplication).container.readiness.refresh()
    }

    private class AppSettings(val theme: ThemeMode, val timeFormat: TimeFormat, val setupStep: SetupStep?)

    private fun appSettings(store: SettingsStore) = combine(
        store.flow(Settings.theme),
        store.flow(Settings.timeFormat),
        SetupStep.current(store),
        ::AppSettings,
    )

    private companion object {
        // The scrims enableEdgeToEdge() uses by default behind a three-button navigation bar.
        val LightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
