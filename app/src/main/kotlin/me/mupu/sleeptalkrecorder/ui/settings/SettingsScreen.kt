package me.mupu.sleeptalkrecorder.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon

/** The settings screen; it has no settings yet. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { AppBar(stringResource(R.string.settings), navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}
