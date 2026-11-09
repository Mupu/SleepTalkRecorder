package me.mupu.sleeptalkrecorder.whatsnew

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangelogTest {

    private val changelog = """
        # Changelog

        What changed for users in each release.
        Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

        ## [0.2.0] - 2026-12-01

        ### Added

        - Show **what is new** after an update.
        - A list of all updates.

        ### Fixed

        - The timeline no longer jumps.

        ## [0.1.0] - 2026-11-09

        The first beta: the app records your nights
        and lets you hear only what was said.

        ### Added

        - Record a whole night.
    """.trimIndent()

    @Test
    fun `every release has its version, date and blocks, newest first`() {
        val releases = Changelog.parse(changelog)

        assertEquals(listOf(Version(0, 2, 0), Version(0, 1, 0)), releases.map { it.version })
        assertEquals(LocalDate.of(2026, 12, 1), releases[0].date)
        assertEquals(
            listOf(
                Block.Heading(plain("Added")),
                Block.Bullet(listOf(Run("Show "), Run("what is new", strong = true), Run(" after an update."))),
                Block.Bullet(plain("A list of all updates.")),
                Block.Heading(plain("Fixed")),
                Block.Bullet(plain("The timeline no longer jumps.")),
            ),
            releases[0].blocks,
        )
    }

    @Test
    fun `the lines of a paragraph are joined and the preamble before the first release is left out`() {
        val oldest = Changelog.parse(changelog).last()

        assertEquals(
            listOf(
                Block.Paragraph(plain("The first beta: the app records your nights and lets you hear only what was said.")),
                Block.Heading(plain("Added")),
                Block.Bullet(plain("Record a whole night.")),
            ),
            oldest.blocks,
        )
    }

    @Test
    fun `an indented line continues the bullet above it`() {
        val release = Changelog.parse("## [1.0.0] - 2027-01-01\n\n- One item\n  over two lines.\n- Next").single()

        assertEquals(
            listOf(Block.Bullet(plain("One item over two lines.")), Block.Bullet(plain("Next"))),
            release.blocks,
        )
    }

    @Test
    fun `asterisk bullets count as bullets too`() {
        val release = Changelog.parse("## [1.0.0] - 2027-01-01\n\n* Item").single()

        assertEquals(listOf(Block.Bullet(plain("Item"))), release.blocks)
    }

    @Test
    fun `emphasis, strong emphasis and inline code are recognised`() {
        assertEquals(
            listOf(
                Run("Tap "),
                Run("Play", emphasis = true),
                Run(" or "),
                Run("Stop", strong = true),
                Run(", see "),
                Run("lead_in", code = true),
                Run("."),
            ),
            Changelog.inline("Tap *Play* or __Stop__, see `lead_in`."),
        )
    }

    @Test
    fun `emphasis nests in strong emphasis and code keeps its asterisks`() {
        assertEquals(
            listOf(Run("very ", strong = true), Run("much", strong = true, emphasis = true), Run("a*b", code = true)),
            Changelog.inline("**very _much_**`a*b`"),
        )
    }

    @Test
    fun `underscores inside a word and unclosed markers stay text`() {
        assertEquals(plain("snake_case_name and a * star and **open"), Changelog.inline("snake_case_name and a * star and **open"))
    }

    @Test
    fun `a heading without a valid date has no date, and other second-level headings start no release`() {
        val releases = Changelog.parse("## [1.0.0]\n\n- A\n\n## Unreleased\n\n- B\n\n## [0.9.0] - 2026-13-40\n\n- C")

        assertEquals(listOf(Version(1, 0, 0), Version(0, 9, 0)), releases.map { it.version })
        assertNull(releases[0].date)
        assertEquals(listOf(Block.Bullet(plain("A"))), releases[0].blocks)
        assertNull(releases[1].date)
    }

    @Test
    fun `an empty or unrelated text has no releases`() {
        assertEquals(emptyList<Release>(), Changelog.parse(""))
        assertEquals(emptyList<Release>(), Changelog.parse("# Changelog\n\nNothing yet."))
    }

    @Test
    fun `the releases newer than a version are the ones a user has not seen`() {
        val releases = Changelog.parse(changelog)

        assertEquals(listOf(Version(0, 2, 0)), releases.newerThan(Version(0, 1, 0)).map { it.version })
        assertEquals(listOf(Version(0, 2, 0), Version(0, 1, 0)), releases.newerThan(Version(0, 0, 9)).map { it.version })
        assertEquals(emptyList<Release>(), releases.newerThan(Version(0, 2, 0)))
    }

    @Test
    fun `versions compare by major, minor and patch as numbers`() {
        assertEquals(Version(0, 10, 0), Version.parse("0.10.0"))
        assertEquals(true, Version(0, 10, 0) > Version(0, 9, 12))
        assertEquals(true, Version(1, 0, 0) > Version(0, 99, 99))
        assertEquals("1.2.3", Version(1, 2, 3).toString())
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("v1.2.3"))
    }

    private fun plain(text: String) = listOf(Run(text))
}
