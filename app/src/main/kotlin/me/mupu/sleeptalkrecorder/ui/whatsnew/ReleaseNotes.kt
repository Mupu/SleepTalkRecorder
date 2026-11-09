package me.mupu.sleeptalkrecorder.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.whatsnew.Block
import me.mupu.sleeptalkrecorder.whatsnew.Release
import me.mupu.sleeptalkrecorder.whatsnew.Run
import java.time.format.DateTimeFormatter

/** "Version 0.2.0 · 1 December 2026", the line above a release. */
@Composable
internal fun releaseTitle(release: Release): String {
    val resources = LocalResources.current
    val version = resources.getString(R.string.whats_new_version, release.version.toString())
    val date = release.date ?: return version
    val pattern = DateTimeFormatter.ofPattern(resources.getString(R.string.date_pattern_release), resources.configuration.locales[0])
    return "$version · ${date.format(pattern)}"
}

/** The text of a release: its intro, the headings of its sections and their items. */
@Composable
internal fun ReleaseBlocks(release: Release, modifier: Modifier = Modifier) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        release.blocks.forEachIndexed { index, block ->
            val text = block.text.styled(codeBackground)
            when (block) {
                is Block.Heading -> Text(
                    text,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                )
                is Block.Paragraph -> Text(text, style = MaterialTheme.typography.bodyMedium)
                is Block.Bullet -> Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The runs as one text: strong in bold, emphasis in italics, code in a monospace font on a tint. */
private fun List<Run>.styled(codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (run in this@styled) {
        val style = SpanStyle(
            fontWeight = if (run.strong) FontWeight.Bold else null,
            fontStyle = if (run.emphasis) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) codeBackground else Color.Unspecified,
        )
        withStyle(style) { append(run.text) }
    }
}
