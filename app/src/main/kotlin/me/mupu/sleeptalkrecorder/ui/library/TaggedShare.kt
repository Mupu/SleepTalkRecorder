package me.mupu.sleeptalkrecorder.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import me.mupu.sleeptalkrecorder.ui.share.EventShareSheet
import me.mupu.sleeptalkrecorder.ui.share.startShareChooser
import me.mupu.sleeptalkrecorder.ui.sleep.SleepViewModel

/**
 * The share step of a tagged event, shown over the library. [createViewModel] reads the event's
 * sleep as the sleep screen would and opens the share step once it is read; [onClosed] follows
 * once the step is closed, or once its files went to Android's share sheet, after [onShared], or
 * [onFailed] reported that they could not be written. The sleep is let go of when this leaves the
 * composition.
 */
@Composable
fun TaggedShare(createViewModel: () -> SleepViewModel, onFailed: () -> Unit, onShared: () -> Unit, onClosed: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val viewModel = viewModel { createViewModel() }
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        val share by viewModel.sharing.state.collectAsStateWithLifecycle()
        val positionState = viewModel.position.collectAsStateWithLifecycle()
        val position = remember(positionState) { { positionState.value } }
        val currentOnFailed by rememberUpdatedState(onFailed)
        val currentOnShared by rememberUpdatedState(onShared)
        val currentOnClosed by rememberUpdatedState(onClosed)

        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

        // A step whose files are being written closes once they are handed on, not when it hides.
        var opened by remember { mutableStateOf(false) }
        var writing by remember { mutableStateOf(false) }
        LaunchedEffect(share) {
            val step = share
            if (step != null) {
                opened = true
                writing = step.preparing
            } else if (opened && !writing) {
                currentOnClosed()
            }
        }
        LaunchedEffect(viewModel) {
            viewModel.sharing.shared.collect { files ->
                if (files == null) {
                    currentOnFailed()
                } else {
                    currentOnShared()
                    startShareChooser(context, files)
                }
                currentOnClosed()
            }
        }

        share?.let { step -> EventShareSheet(viewModel.sharing, step, state.loudness, position) }
    }
}
