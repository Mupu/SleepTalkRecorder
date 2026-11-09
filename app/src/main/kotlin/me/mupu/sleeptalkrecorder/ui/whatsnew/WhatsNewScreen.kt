package me.mupu.sleeptalkrecorder.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.whatsnew.Release

/** "What's new": every release of the app with its date and what it brought, newest first. */
@Composable
fun WhatsNewScreen(viewModel: WhatsNewViewModel, onBack: () -> Unit) {
    val releases by viewModel.releases.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppBar(stringResource(R.string.whats_new), navigationIcon = { BackIcon(onBack) }) }) { padding ->
        val shown = releases ?: return@Scaffold
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.whats_new_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.version.toString() }) { release -> ReleaseCard(release) }
        }
    }
}

@Composable
private fun ReleaseCard(release: Release) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(releaseTitle(release), style = MaterialTheme.typography.titleMedium)
            ReleaseBlocks(release)
        }
    }
}

/**
 * The pop-up after an update with the [news], the releases the user has not seen, newest first.
 * Any way of closing it counts as seen; [onOpenAll] also opens the list of all updates.
 */
@Composable
fun WhatsNewDialog(news: List<Release>, onDismiss: () -> Unit, onOpenAll: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_new_releases), contentDescription = null) },
        title = { Text(stringResource(R.string.whats_new_title, news.first().version.toString())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                news.forEachIndexed { index, release ->
                    if (index > 0) HorizontalDivider()
                    // With news of several releases, each says which one it is.
                    if (news.size > 1) {
                        Text(
                            releaseTitle(release),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReleaseBlocks(release)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.whats_new_seen)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenAll()
                },
            ) { Text(stringResource(R.string.whats_new_all)) }
        },
    )
}
