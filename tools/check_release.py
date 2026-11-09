"""Release check: do the changelogs carry the version the commits since the last tag call for?

The newest `## [X.Y.Z] - YYYY-MM-DD` section of CHANGELOG.md is the release a push delivers; the app
reads its version from there as well. A repository without a `vX.Y.Z` tag stands at 0.0.0. The
commits since the last tag decide the next version (Conventional Commits), and the newest section
must be exactly that version:

- before 1.0.0, `feat` and breaking changes (`!` / `BREAKING CHANGE`) raise the minor version and
  `fix` / `perf` the patch version, so the first release is 0.1.0 and nothing reaches 1.0.0 on its own;
- from 1.0.0 on, breaking changes raise the major version, `feat` the minor and `fix` / `perf` the patch;
- the other types (docs, test, ci, chore, refactor, style, build, revert) need no release;
- a `Release-As: X.Y.Z` footer sets the version instead, at least the one the commits call for.
  It is the only way to 1.0.0.

Every app language has its changelog: CHANGELOG.md for the language of the app's default resources,
CHANGELOG.<lang>.md for each translated `values-<lang>` folder. They must have the same section
headings, and no section may be empty.

    python tools/check_release.py               # fails when the changelogs do not fit the commits
    python tools/check_release.py --tag         # prints the version to tag (empty: nothing to tag)
    python tools/check_release.py --notes       # prints the CHANGELOG.md sections since the last tag
    python tools/check_release.py --notes --lang de   # the same sections of CHANGELOG.de.md
    python tools/check_release.py --message F   # commit-msg hook: counts the message in file F too

Only the standard library is used, so it runs from a fresh clone and in CI without setup.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
HEADING = re.compile(r"^## \[(\d+)\.(\d+)\.(\d+)\]", re.MULTILINE)
SUBJECT = re.compile(r"^(?P<type>\w+)(\([^)]*\))?(?P<bang>!)?:")
RELEASE_AS = re.compile(r"^Release-As:\s*(\d+)\.(\d+)\.(\d+)\s*$", re.MULTILINE | re.IGNORECASE)
TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)")
LANGUAGE_FOLDER = re.compile(r"values-(?P<lang>[a-z]{2,3})(?:-r(?P<region>[A-Z]{2}))?")

Version = tuple[int, int, int]
UNRELEASED: Version = (0, 0, 0)


def fmt(v: Version) -> str:
    return ".".join(map(str, v))


def required_bump(messages: list[str]) -> str | None:
    """'major', 'minor', 'patch' or None for the given full commit messages."""
    bump = None
    for msg in messages:
        m = SUBJECT.match(msg)
        if not m:
            continue
        if m["bang"] or "BREAKING CHANGE" in msg:
            return "major"
        if m["type"] == "feat":
            bump = "minor"
        elif m["type"] in ("fix", "perf") and bump is None:
            bump = "patch"
    return bump


def release_as(messages: list[str]) -> Version | None:
    """The highest version a `Release-As:` footer of the messages names, if any."""
    pinned = [(int(m[1]), int(m[2]), int(m[3])) for msg in messages for m in RELEASE_AS.finditer(msg)]
    return max(pinned, default=None)


def next_version(last: Version, bump: str | None) -> Version:
    """The version the bump calls for over the last release; a beta (0.x) never leaves 0 by itself."""
    major, minor, patch = last
    if bump == "major" and major == 0:
        bump = "minor"
    if bump == "major":
        return (major + 1, 0, 0)
    if bump == "minor":
        return (major, minor + 1, 0)
    if bump == "patch":
        return (major, minor, patch + 1)
    return last


def check(last: Version | None, current: Version | None, messages: list[str]) -> str | None:
    """An error message, or None when the newest CHANGELOG.md section fits the commits since the last tag.

    `last` is the last tagged version (None: no tag yet), `current` the newest section's version
    (None: no section yet), `messages` the full messages of the commits since the last tag.
    """
    base = last or UNRELEASED
    current = current or UNRELEASED
    since = f"v{fmt(last)}" if last else "the start"
    if current < base:
        return f"CHANGELOG.md version {fmt(current)} is older than the last tag v{fmt(base)}"
    expected = next_version(base, required_bump(messages))
    pinned = release_as(messages)
    if pinned:
        if pinned <= base:
            return f"Release-As: {fmt(pinned)} is not above the last release {fmt(base)}"
        if pinned < expected:
            return f"Release-As: {fmt(pinned)} is below the {fmt(expected)} the commits since {since} call for"
        expected = pinned
    if current == expected:
        return None
    if expected == base:
        return f"no commit since {since} calls for a release, but CHANGELOG.md has a section for {fmt(current)}"
    error = f"commits since {since} call for {fmt(expected)}, but the newest CHANGELOG.md section is {fmt(current) if current != UNRELEASED else 'missing'}"
    if base[0] == 0 and current[0] > 0 and not pinned:
        error += f"; {fmt(current)} is released only with a 'Release-As: {fmt(current)}' footer"
    return error


def sections(text: str) -> list[tuple[str, str]]:
    """The `## [X.Y.Z]` sections of a changelog, newest first, as (heading line, body)."""
    result = []
    for part in re.split(r"(?=^## \[)", text, flags=re.MULTILINE)[1:]:
        heading, _, body = part.partition("\n")
        result.append((heading.strip(), body))
    return result


def check_changelogs(texts: dict[str, str | None]) -> list[str]:
    """Errors of the changelogs, given as {file name: text or None when missing}; the first is CHANGELOG.md.

    Every changelog has the same section headings as the first, and every section says something.
    """
    errors = []
    reference_name, reference = next(iter(texts.items()))
    reference_headings = [h for h, _ in sections(reference or "")]
    for name, text in texts.items():
        if text is None:
            errors.append(f"{name} is missing")
            continue
        found = sections(text)
        headings = [h for h, _ in found]
        if headings != reference_headings:
            errors.append(f"{name} has the sections {headings or 'none'}, {reference_name} has {reference_headings or 'none'}")
        for heading, body in found:
            if not any(line.strip() and not line.startswith("#") for line in body.splitlines()):
                errors.append(f"{name}: the section '{heading}' is empty")
    return errors


def app_languages(res: Path) -> list[str]:
    """The app's languages: that of its default resources first, then each translated values folder's."""
    properties = (res / "resources.properties").read_text(encoding="utf-8")
    default = re.search(r"^unqualifiedResLocale\s*=\s*(\S+)", properties, re.MULTILINE)
    if not default:
        raise ValueError(f"{res / 'resources.properties'} names no unqualifiedResLocale")
    translated = []
    for folder in res.iterdir():
        m = LANGUAGE_FOLDER.fullmatch(folder.name)
        if m and (folder / "strings.xml").is_file():
            translated.append(m["lang"] + (f"-{m['region']}" if m["region"] else ""))
    return [default[1], *sorted(translated)]


def changelog_name(lang: str, languages: list[str]) -> str:
    return "CHANGELOG.md" if lang == languages[0] else f"CHANGELOG.{lang}.md"


def notes(text: str, last: Version | None) -> str:
    """The sections of a changelog newer than the last tag."""
    kept = []
    for heading, body in sections(text):
        m = HEADING.match(heading)
        if m and (last is None or (int(m[1]), int(m[2]), int(m[3])) > last):
            kept.append(f"{heading}\n{body}".strip())
    return "\n\n".join(kept)


def _git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def _read(path: Path) -> str | None:
    return path.read_text(encoding="utf-8") if path.is_file() else None


def main() -> int:
    parser = argparse.ArgumentParser(description="Release check of the changelogs against the commits since the last tag.")
    parser.add_argument("--tag", action="store_true", help="print the version to tag (nothing when there is none)")
    parser.add_argument("--notes", action="store_true", help="print the changelog sections since the last tag")
    parser.add_argument("--lang", help="language of --notes (default: the app's default language)")
    parser.add_argument("--message", metavar="FILE", help="count the commit message being written in FILE too")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")  # the notes are UTF-8 whatever the console's code page  # type: ignore[union-attr]

    tags = [t for t in _git("tag", "--list", "v*", "--sort=-v:refname").split() if TAG.fullmatch(t)]
    last = tuple(int(x) for x in TAG.fullmatch(tags[0]).groups()) if tags else None  # type: ignore[union-attr]

    has_head = subprocess.run(["git", "rev-parse", "--verify", "-q", "HEAD"], cwd=ROOT, capture_output=True).returncode == 0
    messages = _git("log", "--format=%B%x00", f"{tags[0]}..HEAD" if tags else "HEAD").split("\0") if has_head else []
    if args.message:  # commit-msg hook: the commit being made counts too
        pending = Path(args.message).read_text(encoding="utf-8")
        messages.append("\n".join(line for line in pending.splitlines() if not line.startswith("#")))

    languages = app_languages(RES)
    texts = {changelog_name(lang, languages): _read(ROOT / changelog_name(lang, languages)) for lang in languages}
    versions = [m.groups() for m in HEADING.finditer(texts["CHANGELOG.md"] or "")]
    current = tuple(map(int, versions[0])) if versions else None

    errors = check_changelogs(texts)
    error = check(last, current, [m.strip() for m in messages if m.strip()])  # type: ignore[arg-type]
    if error:
        errors.insert(0, error)
    if args.lang and args.lang not in languages:
        errors.append(f"--lang {args.lang} is no app language; the app has {', '.join(languages)}")
    for e in errors:
        print(f"::error::{e}", file=sys.stderr)
    if errors:
        return 1
    if args.tag and current and current != last:
        print("v" + fmt(current))  # type: ignore[arg-type]
    if args.notes:
        print(notes(texts[changelog_name(args.lang or languages[0], languages)] or "", last))  # type: ignore[arg-type]
    return 0


if __name__ == "__main__":
    sys.exit(main())
