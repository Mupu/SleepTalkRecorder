package me.mupu.sleeptalkrecorder.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** The colours of a shared event's card as ARGB values: those of the app's dark theme. */
data class CardColors(val background: Int, val name: Int, val time: Int, val transcript: Int)

/**
 * The still picture of a shared event's video, [WIDTH] by [HEIGHT] pixels: the favourite's name,
 * or the event's kind without one, top left in bold, the date and clock time with the transcript
 * below them bottom right.
 *
 * Its shape and layout follow how WhatsApp shows a video in a chat: a preview up to 4:1 is shown
 * whole, a wider one is cropped left and right. WhatsApp draws its play button in the middle and
 * the video's length at the bottom left, so the name ends before the middle and the bottom left
 * stays free.
 */
internal object ShareCard {

    const val WIDTH = 1200
    const val HEIGHT = 300

    /** Draws the card of [event] in [colors]. */
    fun draw(event: EventLabels, colors: CardColors): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.background)

        // The kind stands in for a missing name, so no card looks empty.
        drawName(canvas, event.name?.takeIf { it.isNotBlank() } ?: event.kind, colors.name)

        // Time and transcript stand right of the middle, ending at the bottom margin.
        val right = WIDTH - MARGIN_X
        var bottom = (HEIGHT - MARGIN_Y).toFloat()
        event.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
            val paint = textPaint(TEXT_SIZE, colors.transcript, bold = false)
            val layout = StaticLayout.Builder.obtain(transcript, 0, transcript.length, paint, SIDE_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setMaxLines(TRANSCRIPT_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate((right - SIDE_WIDTH).toFloat(), bottom - layout.height)
            layout.draw(canvas)
            canvas.restore()
            bottom -= layout.height + LINE_GAP
        }

        val timePaint = textPaint(TEXT_SIZE, colors.time, bold = false).apply { textAlign = Paint.Align.RIGHT }
        val time = TextUtils.ellipsize(event.time, timePaint, SIDE_WIDTH.toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(time, right.toFloat(), bottom - timePaint.fontMetrics.descent, timePaint)
        return bitmap
    }

    /** The name top left, made smaller down to [NAME_MIN_SIZE] to fit before the middle, then shortened. */
    private fun drawName(canvas: Canvas, name: String, color: Int) {
        val paint = textPaint(NAME_SIZE, color, bold = true)
        val width = paint.measureText(name)
        if (width > NAME_WIDTH) paint.textSize = maxOf(NAME_MIN_SIZE, NAME_SIZE * NAME_WIDTH / width)
        val shown = TextUtils.ellipsize(name, paint, NAME_WIDTH.toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, MARGIN_X.toFloat(), MARGIN_Y - paint.fontMetrics.ascent, paint)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** The space to the left and right edges, 5 % of the width, and to the top and bottom ones. */
    private const val MARGIN_X = WIDTH / 20
    private const val MARGIN_Y = 45

    /**
     * How wide the name and the text on the right may be: each half of the card less the margin
     * and the half of WhatsApp's play button that reaches into it.
     */
    private const val NAME_WIDTH = WIDTH / 2 - MARGIN_X - 60
    private const val SIDE_WIDTH = NAME_WIDTH

    private const val NAME_SIZE = 56f
    private const val NAME_MIN_SIZE = 40f
    private const val TEXT_SIZE = 40f
    private const val LINE_GAP = 6f
    private const val TRANSCRIPT_LINES = 2
}
