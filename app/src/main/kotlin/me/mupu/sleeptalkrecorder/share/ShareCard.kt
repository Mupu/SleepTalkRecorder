package me.mupu.sleeptalkrecorder.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** The colours of a shared event's card as ARGB values: those of the app's dark theme. */
data class CardColors(val background: Int, val name: Int, val time: Int, val transcript: Int)

/**
 * The shape of a shared event's card, [width] by [height] pixels, both even as the video encoder
 * needs them.
 *
 * The shapes follow how WhatsApp shows videos in a chat: a single video in its own aspect up to
 * 4:1, several sent together as an album whose tiles crop each video to the tile's shape. An album
 * of two stacks two tiles of about 2:1; one of three puts a 2:1 tile over two squares when its
 * first video is landscape; one of four or more is a grid of four squares, the fourth covered by
 * the count of the videos not shown.
 */
enum class CardShape(val width: Int, val height: Int) {
    /** 4:1, the card of an event shared alone. */
    WIDE(1200, 300),

    /** 2:1, the tile of an album of two, or the top one of three. */
    LANDSCAPE(1200, 600),

    /** 1:1, the lower tiles of an album of three and every tile of a larger one. */
    SQUARE(720, 720),
}

/** The shapes of the cards of [count] events shared together as videos, in their order, so the album shows each whole. */
fun cardShapes(count: Int): List<CardShape> = when (count) {
    1 -> listOf(CardShape.WIDE)
    2 -> listOf(CardShape.LANDSCAPE, CardShape.LANDSCAPE)
    3 -> listOf(CardShape.LANDSCAPE, CardShape.SQUARE, CardShape.SQUARE)
    else -> List(count) { CardShape.SQUARE }
}

/**
 * The still picture of a shared event's video in one of the [CardShape]s: the event's name,
 * or the event's kind without one, top left in bold, the date and clock time with the transcript
 * below them bottom right, and for an event shared with others its number in a disc top right, the
 * number its caption line starts with.
 *
 * WhatsApp draws its play button in the middle of a video and the video's length at the bottom
 * left, so the bottom left stays free, and on the wide card the name ends before the middle. The
 * taller shapes are shown as small album tiles, so their text is larger for their size and wraps
 * onto more lines instead of being cut.
 */
internal object ShareCard {

    /** Draws the card of [event] in [colors] as [shape], with [number] when it is shared with others. */
    fun draw(event: EventLabels, colors: CardColors, shape: CardShape, number: Int?): Bitmap {
        require(number == null || shape != CardShape.WIDE) { "the wide card of a single event has no number" }
        val layout = layoutOf(shape)
        val bitmap = Bitmap.createBitmap(shape.width, shape.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.background)

        // The kind stands in for a missing name, so no card looks empty.
        drawName(canvas, event.name?.takeIf { it.isNotBlank() } ?: event.kind, colors.name, layout)
        number?.let { drawNumber(canvas, it, colors, shape, layout) }

        // Time and transcript stand on the right, ending at the bottom margin.
        val right = shape.width - layout.marginX
        var bottom = (shape.height - layout.marginY).toFloat()
        event.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
            val paint = textPaint(layout.textSize, colors.transcript, bold = false)
            val text = StaticLayout.Builder.obtain(transcript, 0, transcript.length, paint, layout.sideWidth)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setMaxLines(layout.transcriptLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate((right - layout.sideWidth).toFloat(), bottom - text.height)
            text.draw(canvas)
            canvas.restore()
            bottom -= text.height + LINE_GAP
        }

        val timePaint = textPaint(layout.textSize, colors.time, bold = false).apply { textAlign = Paint.Align.RIGHT }
        val time = TextUtils.ellipsize(event.time, timePaint, layout.sideWidth.toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(time, right.toFloat(), bottom - timePaint.fontMetrics.descent, timePaint)
        return bitmap
    }

    /**
     * The name top left, wrapped onto up to [CardLayout.nameLines] lines and then shortened; a
     * single line is first made smaller down to [CardLayout.nameMinSize] to fit.
     */
    private fun drawName(canvas: Canvas, name: String, color: Int, layout: CardLayout) {
        val paint = textPaint(layout.nameSize, color, bold = true)
        if (layout.nameLines == 1) {
            val width = paint.measureText(name)
            if (width > layout.nameWidth) paint.textSize = maxOf(layout.nameMinSize, layout.nameSize * layout.nameWidth / width)
        }
        val text = StaticLayout.Builder.obtain(name, 0, name.length, paint, layout.nameWidth)
            .setIncludePad(false)
            .setMaxLines(layout.nameLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(layout.marginX.toFloat(), layout.marginY.toFloat())
        text.draw(canvas)
        canvas.restore()
    }

    /** [number] in bold, in a disc of the name's colour top right, made smaller where it has more digits. */
    private fun drawNumber(canvas: Canvas, number: Int, colors: CardColors, shape: CardShape, layout: CardLayout) {
        val radius = layout.badge / 2f
        val centerX = shape.width - layout.marginX - radius
        val centerY = layout.marginY + radius
        canvas.drawCircle(centerX, centerY, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.name })

        val digits = number.toString()
        val paint = textPaint(layout.badge * NUMBER_SHARE, colors.background, bold = true).apply { textAlign = Paint.Align.CENTER }
        val width = paint.measureText(digits)
        if (width > layout.badge * NUMBER_WIDTH) paint.textSize *= layout.badge * NUMBER_WIDTH / width
        // Centred on the height of the digits, which have no descent.
        val bounds = Rect().also { paint.getTextBounds(digits, 0, digits.length, it) }
        canvas.drawText(digits, centerX, centerY + bounds.height() / 2f, paint)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /**
     * Where a card of a shape draws what, in pixels: the space to its edges, the name's size and
     * width, that of the time and transcript on the right, and the number's disc ([badge] across).
     */
    private class CardLayout(
        val marginX: Int,
        val marginY: Int,
        val nameSize: Float,
        val nameMinSize: Float,
        val nameLines: Int,
        val nameWidth: Int,
        val textSize: Float,
        val sideWidth: Int,
        val transcriptLines: Int,
        val badge: Int,
    )

    private fun layoutOf(shape: CardShape): CardLayout = when (shape) {
        // The margin to the sides is 5 % of the width; the name and the text on the right each
        // take half the card less the margin and the half of WhatsApp's play button that reaches
        // into it.
        CardShape.WIDE -> CardLayout(
            marginX = 60, marginY = 45,
            nameSize = 56f, nameMinSize = 40f, nameLines = 1, nameWidth = 480,
            textSize = 40f, sideWidth = 480, transcriptLines = 2,
            badge = 0,
        )
        // Shown about two thirds of its size across a chat bubble, so its text is larger.
        CardShape.LANDSCAPE -> CardLayout(
            marginX = 60, marginY = 50,
            nameSize = 84f, nameMinSize = 84f, nameLines = 2, nameWidth = 1200 - 2 * 60 - 220 - NAME_GAP,
            textSize = 56f, sideWidth = 760, transcriptLines = 2,
            badge = 220,
        )
        // Shown about half its size in a quarter of the bubble, so its text is larger still.
        CardShape.SQUARE -> CardLayout(
            marginX = 48, marginY = 48,
            nameSize = 72f, nameMinSize = 72f, nameLines = 3, nameWidth = 720 - 2 * 48 - 200 - NAME_GAP,
            textSize = 56f, sideWidth = 528, transcriptLines = 3,
            badge = 200,
        )
    }

    /** The space between the name and the number's disc. */
    private const val NAME_GAP = 32

    private const val LINE_GAP = 6f

    /** The number's size as a share of its disc, and the widest it may be before it is made smaller. */
    private const val NUMBER_SHARE = 0.6f
    private const val NUMBER_WIDTH = 0.72f
}
