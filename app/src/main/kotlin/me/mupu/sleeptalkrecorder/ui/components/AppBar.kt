package me.mupu.sleeptalkrecorder.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.mupu.sleeptalkrecorder.R

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
