package me.mupu.sleeptalkrecorder.ui.components

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * The label of a segmented button. Labels share a row, so a long one (German ones often are, and
 * so is any label at a large font size) first breaks into a second line between its words and
 * only then shrinks, instead of being cut off. A single word shrinks right away, since breaking it
 * would split it mid-word.
 */
@Composable
fun SegmentLabel(text: String) {
    // Centred also in a button stretched to the height of a two-line neighbour.
    FittingText(text, Modifier.wrapContentHeight(), maxLines = if (' ' in text.trim()) 2 else 1, textAlign = TextAlign.Center)
}

/**
 * [text] in [style], shrunk step by step down to 10 sp when it does not fit in [maxLines]
 * in the width it gets, so that a value such as a time range is never cut off.
 */
@Composable
fun FittingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        textAlign = textAlign,
        autoSize = TextAutoSize.StepBased(
            minFontSize = MIN_SIZE,
            maxFontSize = if (style.fontSize.isSpecified) style.fontSize else DEFAULT_SIZE,
            stepSize = 1.sp,
        ),
    )
}

/** The smallest size text shrinks to; below it, a label is hard to read on a phone. */
private val MIN_SIZE = 10.sp

/** The size of a style that names none, as Material's body text has. */
private val DEFAULT_SIZE = 14.sp
