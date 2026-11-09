package me.mupu.sleeptalkrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import me.mupu.sleeptalkrecorder.ui.SleepTalkNavHost
import me.mupu.sleeptalkrecorder.ui.theme.SleepTalkRecorderTheme

/** The app's only activity; every screen is a Compose destination inside it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SleepTalkApplication).container
        setContent {
            SleepTalkRecorderTheme {
                SleepTalkNavHost(container)
            }
        }
    }
}
