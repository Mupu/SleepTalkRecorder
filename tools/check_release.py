"""Release check: does CHANGELOG.md carry the version the commits since the last tag call for?

The newest `## [X.Y.Z] - YYYY-MM-DD` section of CHANGELOG.md is the release a push delivers; the app
reads its version from there as well. Commits since the last `vX.Y.Z` tag decide the minimum bump
(Conventional Commits): `!` / `BREAKING CHANGE` -> major, `feat` -> minor, `fix` / `perf` -> patch;
the other types (docs, test, ci, chore, refactor, style, build, revert) need no release.

Nothing is released before 1.0.0, the first version the owner uses on their phone: until CHANGELOG.md
has its first section, no commit needs one, and that first section must be 1.0.0.

    python tools/check_release.py               # fails when a release is due but CHANGELOG.md lacks it
    python tools/check_release.py --tag         # prints the version to tag (empty: nothing to tag)
    python tools/check_release.py --notes       # prints the CHANGELOG.md sections since the last tag
    python tools/check_release.py --message F   # commit-msg hook: counts the message in file F too

Only the standard library is used, so it runs from a fresh clone and in CI without setup.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HEADING = re.compile(r"^## \[(\d+)\.(\d+)\.(\d+)\]", re.MULTILINE)
SUBJECT = re.compile(r"^(?P<type>\w+)(\([^)]*\))?(?P<bang>!)?:")
TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)")

Version = tuple[int, int, int]


def changelog_versions(text: str) -> list[Version]:
    """All section versions of CHANGELOG.md, newest first."""
    return [(int(m[1]), int(m[2]), int(m[3])) for m in HEADING.finditer(text)]


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


FIRST_RELEASE: Version = (1, 0, 0)


def bumped(v: Version, bump: str) -> Version:
    major, minor, patch = v
    return {"major": (major + 1, 0, 0), "minor": (major, minor + 1, 0), "patch": (major, minor, patch + 1)}[bump]


def fmt(v: Version) -> str:
    return ".".join(map(str, v))


def check(last: Version | None, current: Version | None, bump: str | None) -> str | None:
    """An error message, or None when CHANGELOG.md fits the commits."""
    if last is None:
        if current is None:
            return None  # before the first release nothing needs a changelog section
        if current != FIRST_RELEASE:
            return f"the first release is {fmt(FIRST_RELEASE)}, CHANGELOG.md says {fmt(current)}"
        return None
    if current is None:
        return f"CHANGELOG.md has no section, but v{fmt(last)} was released"
    if current < last:
        return f"CHANGELOG.md version {fmt(current)} is older than the last tag v{fmt(last)}"
    if bump and current < bumped(last, bump):
        if current == last:
            return f"commits since v{fmt(last)} need a {bump} release: add a CHANGELOG.md section for {fmt(bumped(last, bump))}"
        return f"commits since v{fmt(last)} need a {bump} release >= {fmt(bumped(last, bump))}, CHANGELOG.md says {fmt(current)}"
    return None


def notes(text: str, last: Version | None) -> str:
    """The CHANGELOG.md sections newer than the last tag, without their headings' surroundings."""
    sections = re.split(r"(?=^## \[)", text, flags=re.MULTILINE)[1:]
    kept = []
    for section in sections:
        m = HEADING.match(section)
        if m and (last is None or (int(m[1]), int(m[2]), int(m[3])) > last):
            kept.append(section.strip())
    return "\n\n".join(kept)


def _git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def main() -> int:
    tags = [t for t in _git("tag", "--list", "v*", "--sort=-v:refname").split() if TAG.fullmatch(t)]
    last = tuple(int(x) for x in TAG.fullmatch(tags[0]).groups()) if tags else None  # type: ignore[union-attr]

    has_head = subprocess.run(["git", "rev-parse", "--verify", "-q", "HEAD"], cwd=ROOT, capture_output=True).returncode == 0
    messages = _git("log", "--format=%B%x00", f"{tags[0]}..HEAD" if tags else "HEAD").split("\0") if has_head else []
    if "--message" in sys.argv:  # commit-msg hook: the commit being made counts too
        pending = Path(sys.argv[sys.argv.index("--message") + 1]).read_text(encoding="utf-8")
        messages.append("\n".join(line for line in pending.splitlines() if not line.startswith("#")))

    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    versions = changelog_versions(text)
    current = versions[0] if versions else None
    bump = required_bump([m.strip() for m in messages if m.strip()])

    error = check(last, current, bump)  # type: ignore[arg-type]
    if error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    if "--tag" in sys.argv and current and current != last:
        print("v" + fmt(current))
    if "--notes" in sys.argv:
        print(notes(text, last))  # type: ignore[arg-type]
    return 0


if __name__ == "__main__":
    sys.exit(main())
