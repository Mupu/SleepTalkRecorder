package me.mupu.sleeptalkrecorder.whatsnew

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One release of a changelog: its [version], the day it was released and what it brought. */
data class Release(
    val version: Version,
    /** The day in the heading, or null when it has none or none that exists. */
    val date: LocalDate?,
    /** The release's text in the order written: its intro, the headings of its sections and their items. */
    val blocks: List<Block>,
)

/** A block of a release's text. */
sealed interface Block {
    /** The text of the block, in runs of one style each. */
    val text: List<Run>

    /** The heading of a section such as "Added" (`### Added`). */
    data class Heading(override val text: List<Run>) : Block

    /** One item of a list (`- item`). */
    data class Bullet(override val text: List<Run>) : Block

    /** A paragraph, its lines joined into one. */
    data class Paragraph(override val text: List<Run>) : Block
}

/** A piece of text in one style: plain, [strong] (`**`), [emphasis] (`*`) or [code] (`` ` ``). */
data class Run(
    val text: String,
    val strong: Boolean = false,
    val emphasis: Boolean = false,
    val code: Boolean = false,
)

/**
 * Reads the changelogs the app bundles (`CHANGELOG.md` and its translations) in the Markdown subset
 * they are written in: `## [X.Y.Z] - YYYY-MM-DD` starts a release, deeper headings start its
 * sections, and its text is paragraphs and `-` lists with emphasis, strong emphasis and inline code.
 * Anything else stays plain text, so a changelog can never fail to show.
 */
object Changelog {

    private val RELEASE = Regex("""^##\s+\[(\d+\.\d+\.\d+)](?:\s+-\s+(\S+))?\s*$""")
    private val HEADING = Regex("""^(#+)\s+(.*?)\s*#*\s*$""")
    private val BULLET = Regex("""^[-*+]\s+(.*)$""")

    /** The releases in [markdown], newest first as a changelog lists them. */
    fun parse(markdown: String): List<Release> {
        val releases = mutableListOf<Release>()
        var version: Version? = null
        var date: LocalDate? = null
        val blocks = mutableListOf<Block>()

        // The block being read: a bullet or paragraph that the next line may continue.
        var open: ((List<Run>) -> Block)? = null
        val openText = StringBuilder()

        fun closeBlock() {
            open?.let { blocks += it(inline(openText.toString())) }
            open = null
            openText.clear()
        }

        fun closeRelease() {
            closeBlock()
            version?.let { releases += Release(it, date, blocks.toList()) }
            version = null
            blocks.clear()
        }

        for (rawLine in markdown.lines()) {
            val line = rawLine.trim()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)

            when {
                heading != null && heading.groupValues[1].length <= 2 -> {
                    // A heading of the first or second level ends the release before it; only a
                    // release heading starts a new one, so the text of any other is left out.
                    closeRelease()
                    RELEASE.matchEntire(line)?.let { release ->
                        version = Version.parse(release.groupValues[1])
                        date = release.groupValues[2].toDate()
                    }
                }
                version == null -> Unit
                heading != null -> {
                    closeBlock()
                    blocks += Block.Heading(inline(heading.groupValues[2]))
                }
                bullet != null -> {
                    closeBlock()
                    open = Block::Bullet
                    openText.append(bullet.groupValues[1])
                }
                line.isEmpty() -> closeBlock()
                open != null -> openText.append(' ').append(line)
                else -> {
                    open = Block::Paragraph
                    openText.append(line)
                }
            }
        }
        closeRelease()
        return releases
    }

    /**
     * The runs of one line of Markdown text. A marker counts only where it opens and closes as in
     * CommonMark (no space inside it, `_` not within a word); every other marker stays text.
     */
    fun inline(text: String): List<Run> = merge(inline(text, strong = false, emphasis = false))

    private fun inline(text: String, strong: Boolean, emphasis: Boolean): List<Run> {
        val runs = mutableListOf<Run>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) runs += Run(plain.toString(), strong, emphasis)
            plain.clear()
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]

            if (char == '`') {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    flush()
                    runs += Run(text.substring(index + 1, end), strong, emphasis, code = true)
                    index = end + 1
                    continue
                }
            }

            if (char == '*' || char == '_') {
                val marker = if (text.startsWith("$char$char", index)) "$char$char" else "$char"
                val close = closingMarker(text, index, marker)
                if (close >= 0) {
                    flush()
                    val inner = text.substring(index + marker.length, close)
                    runs += inline(inner, strong || marker.length == 2, emphasis || marker.length == 1)
                    index = close + marker.length
                    continue
                }
            }

            plain.append(char)
            index++
        }
        flush()
        return runs
    }

    /** Where the [marker] that opens at [start] closes, or -1 if it opens nothing there. */
    private fun closingMarker(text: String, start: Int, marker: String): Int {
        val char = marker[0]
        val after = text.getOrNull(start + marker.length)
        val before = text.getOrNull(start - 1)
        if (after == null || after.isWhitespace()) return -1
        if (char == '_' && before?.isLetterOrDigit() == true) return -1

        var candidate = text.indexOf(marker, start + marker.length + 1)
        while (candidate >= 0) {
            val closesHere = !text[candidate - 1].isWhitespace() &&
                // `_` closes only at a word's end, and a single marker is no half of a double one.
                !(char == '_' && text.getOrNull(candidate + marker.length)?.isLetterOrDigit() == true) &&
                !(marker.length == 1 && (text.getOrNull(candidate + 1) == char || text[candidate - 1] == char))
            if (closesHere) return candidate
            candidate = text.indexOf(marker, candidate + 1)
        }
        return -1
    }

    /** Joins neighbouring runs of the same style, which unclosed markers leave behind. */
    private fun merge(runs: List<Run>): List<Run> = runs.fold(mutableListOf()) { merged, run ->
        val last = merged.lastOrNull()
        if (last != null && last.copy(text = run.text) == run) {
            merged[merged.lastIndex] = last.copy(text = last.text + run.text)
        } else {
            merged += run
        }
        merged
    }

    private fun String.toDate(): LocalDate? = try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** The releases after [version], which a user who last opened the app in [version] has not seen. */
fun List<Release>.newerThan(version: Version): List<Release> = filter { it.version > version }
