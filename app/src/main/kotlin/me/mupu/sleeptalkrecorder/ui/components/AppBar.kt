package me.mupu.sleeptalkrecorder.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import java.time.Duration

/** The top app bar every screen uses: an icon on the left, the title and optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    title: String,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

/**
 * The app bar of selection mode: how many events are selected ([count]) and how long they last
 * together ([length]), with a button that ends the selection and the [actions] on the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(count: Int, length: Duration, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    val resources = LocalResources.current
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.selection_title, count, formatEventLength(resources, length)),
                // Smaller than a screen's title, so the count and length fit beside three buttons.
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = { AppBarIcon(R.drawable.ic_close, stringResource(R.string.selection_end), onClose) },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** An icon button with an accessible label. */
@Composable
fun AppBarIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

/** The back arrow of a screen opened from another. */
@Composable
fun BackIcon(onBack: () -> Unit) {
    AppBarIcon(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
}
