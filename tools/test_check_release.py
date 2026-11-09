"""Unit tests of the release rules in check_release.py: python -m unittest discover -s tools"""

import tempfile
import unittest
from pathlib import Path

from check_release import app_languages, check, check_changelogs, notes


class VersionRules(unittest.TestCase):
    def test_the_first_feat_releases_0_1_0(self):
        self.assertIsNone(check(None, (0, 1, 0), ["feat: record a sleep (#1)"]))
        self.assertIn("0.1.0", check(None, None, ["feat: record a sleep (#1)"]))

    def test_nothing_needs_a_release_before_the_first_releasing_commit(self):
        self.assertIsNone(check(None, None, ["docs: add the glossary (#2)"]))

    def test_before_1_0_0_feat_and_breaking_changes_raise_the_minor_version(self):
        self.assertIsNone(check((0, 3, 2), (0, 4, 0), ["feat(player): play events (#5)"]))
        self.assertIsNone(check((0, 3, 2), (0, 4, 0), ["feat(data)!: drop old sleeps (#6)"]))
        self.assertIsNone(check((0, 3, 2), (0, 4, 0), ["fix: keep sleeps (#7)\n\nBREAKING CHANGE: new format"]))

    def test_before_1_0_0_fix_and_perf_raise_the_patch_version(self):
        self.assertIsNone(check((0, 3, 2), (0, 3, 3), ["fix(sleep): keep the zoom (#8)"]))
        self.assertIsNone(check((0, 3, 2), (0, 3, 3), ["perf(sleep): draw faster (#9)"]))

    def test_nothing_reaches_1_0_0_without_release_as(self):
        error = check((0, 9, 0), (1, 0, 0), ["feat!: new storage (#10)"])
        self.assertIn("0.10.0", error)
        self.assertIn("Release-As: 1.0.0", error)

    def test_release_as_sets_the_version(self):
        self.assertIsNone(check((0, 9, 0), (1, 0, 0), ["feat: finish v1 (#11)\n\nRelease-As: 1.0.0"]))
        self.assertIsNone(check((0, 9, 0), (1, 0, 0), ["docs: say v1 is done (#11)\n\nRelease-As: 1.0.0"]))

    def test_release_as_cannot_go_below_the_bump_or_the_last_release(self):
        self.assertIn("below", check((1, 2, 0), (1, 2, 1), ["feat: x (#12)\n\nRelease-As: 1.2.1"]))
        self.assertIn("not above", check((1, 2, 0), (1, 2, 0), ["docs: x (#12)\n\nRelease-As: 1.2.0"]))

    def test_from_1_0_0_on_breaking_changes_raise_the_major_version(self):
        self.assertIsNone(check((1, 2, 3), (2, 0, 0), ["feat(data)!: new storage (#13)"]))
        self.assertIsNone(check((1, 2, 3), (1, 3, 0), ["feat: stats (#14)"]))
        self.assertIsNone(check((1, 2, 3), (1, 2, 4), ["fix: stats (#15)"]))

    def test_the_newest_section_is_exactly_the_version_of_all_commits_since_the_tag(self):
        self.assertIn("0.4.0", check((0, 3, 0), (0, 3, 1), ["fix: a (#16)", "feat: b (#17)"]))
        self.assertIn("0.3.1", check((0, 3, 0), (0, 4, 0), ["fix: a (#16)"]))
        self.assertIn("no commit", check((0, 3, 0), (0, 3, 1), ["docs: a (#18)"]))
        self.assertIn("older", check((0, 3, 0), (0, 2, 0), []))


SECTION = "## [0.2.0] - 2026-12-01\n\n### Added\n\n- Stats.\n\n## [0.1.0] - 2026-11-09\n\n### Added\n\n- Recording.\n"
SECTION_DE = "## [0.2.0] - 2026-12-01\n\n### Hinzugefügt\n\n- Statistik.\n\n## [0.1.0] - 2026-11-09\n\n### Hinzugefügt\n\n- Aufnahme.\n"


class Changelogs(unittest.TestCase):
    def test_changelogs_with_the_same_sections_pass(self):
        self.assertEqual([], check_changelogs({"CHANGELOG.md": SECTION, "CHANGELOG.de.md": SECTION_DE}))

    def test_a_changelog_without_a_section_of_the_others_fails(self):
        de = SECTION_DE.split("## [0.1.0]")[0].replace("0.2.0", "0.1.0", 1)
        errors = check_changelogs({"CHANGELOG.md": SECTION, "CHANGELOG.de.md": "# Changelog\n\n" + de})
        self.assertEqual(1, len(errors))
        self.assertIn("CHANGELOG.de.md", errors[0])

    def test_an_empty_section_fails(self):
        de = SECTION_DE.replace("- Statistik.\n", "")
        self.assertIn("'## [0.2.0] - 2026-12-01' is empty", check_changelogs({"CHANGELOG.md": SECTION, "CHANGELOG.de.md": de})[0])

    def test_a_missing_changelog_fails(self):
        self.assertEqual(["CHANGELOG.de.md is missing"], check_changelogs({"CHANGELOG.md": SECTION, "CHANGELOG.de.md": None}))

    def test_notes_are_the_sections_since_the_last_tag(self):
        self.assertEqual("## [0.2.0] - 2026-12-01\n\n### Hinzugefügt\n\n- Statistik.", notes(SECTION_DE, (0, 1, 0)))


class Languages(unittest.TestCase):
    def test_the_languages_are_the_default_and_every_translated_values_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            res = Path(tmp)
            (res / "resources.properties").write_text("unqualifiedResLocale=en\n", encoding="utf-8")
            for folder in ("values", "values-de", "values-pt-rBR", "values-night", "values-fr"):
                (res / folder).mkdir()
            for folder in ("values", "values-de", "values-pt-rBR", "values-night"):
                (res / folder / "strings.xml").write_text("<resources/>", encoding="utf-8")
            self.assertEqual(["en", "de", "pt-BR"], app_languages(res))


if __name__ == "__main__":
    unittest.main()
