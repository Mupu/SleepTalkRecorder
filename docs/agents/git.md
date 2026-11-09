# Git integration and releases

## Main

There are no pull requests, but `main` reads as if every issue had been one
squash-merged pull request: **one commit per issue** (or per wayfinder ticket
group that makes one feature), with the subject `type(scope): description (#N)`
naming the issue and an empty body; the details stay on the issue.

Work on a local branch per issue (`feature/<n>-<slug>`, `fix/<n>-<slug>`,
`docs/<n>-<slug>`); its commits are free-form. When the work is done and
[the full check](../../README.md#development) passes, squash it onto `main` and
delete the branch:

```sh
git switch main
git merge --squash feature/12-chunk-rotation
git commit -m "feat(recorder): rotate chunk files every 15 minutes (#12)"
git branch -D feature/12-chunk-rotation
git push
```

Every commit on `main` is complete on its own: it builds, its checks pass, it
updates the documents it makes stale and, from 1.0.0 on, carries its
`CHANGELOG.md` section. The history stays linear; never create merge commits.
Work branches are not pushed unless they need to survive the machine
([Prototype branches](#prototype-branches)). Do not rewrite published history on
`main` (no force-push) without the owner's explicit authorization. A larger
change gets a `/code-review` of its diff before it is squashed onto `main`.

Enable the hooks once per clone:

```sh
git config core.hooksPath .githooks
```

The `commit-msg` hook rejects AI or tool attribution in every commit and, on
`main`, runs the release check with the message being written. Skip it only in an emergency
(`git commit --no-verify`), and never to get past an attribution or release error.

## Commit messages

Use [Conventional Commits 1.0](https://www.conventionalcommits.org/):
`type(scope): description` with the types `feat`, `fix`, `perf`, `refactor`,
`docs`, `test`, `build`, `ci`, `chore`, `style` and `revert`; `!` after the type
or a `BREAKING CHANGE:` footer marks an incompatible change. The scope is the
affected feature area or Gradle module once there are several (for example
`feat(recorder): rotate chunk files every 15 minutes`, `fix(detection): keep the
baseline below loud stretches`). General repository, workflow and governance
changes use `type: description` without a scope. Do not invent a repository-wide
scope or use empty parentheses. The rule binds the squash commits on `main`, which
end in ` (#N)`; commits on a work branch are free-form.

The subject says what changes for the user or the code in plain words; the body,
when needed, says why. Commits carry only the human author: no co-author
trailers, generated-by notes or session links.

## Versions and releases

The app has one [Semantic Versioning 2.0](https://semver.org/) version: the
newest `## [X.Y.Z] - YYYY-MM-DD` section of [`CHANGELOG.md`](../../CHANGELOG.md).
The Gradle build reads `versionName` from there and derives `versionCode` from it,
so nobody edits a version anywhere else; tags are `vX.Y.Z`.

**Before the first release** the app is in development and shows version `0.0.0`:
`CHANGELOG.md` stays without sections and no commit needs one. The first release
is `1.0.0`, the first version the owner uses on their phone for real nights; the
owner decides when that is, and its section lists what the app can do then.

**From 1.0.0 on**, a commit's type decides the release it needs:

| Type | Release |
|---|---|
| any type with `!` or `BREAKING CHANGE` | major |
| `feat` | minor |
| `fix`, `perf` | patch |
| `docs`, `refactor`, `test`, `chore`, `ci`, `build`, `style`, `revert` | none |

Versions are never reset. A commit that needs a release adds its `CHANGELOG.md`
section in the same commit:
the version the bump calls for over the last tag, dated with the local date, and
items under `### Added`, `### Changed`, `### Fixed` and `### Removed`. While the
newest section is not tagged yet (its CI run failed or it was not pushed), add new
items to that section, raising its version if the bump grows, instead of opening
another one. `tools/check_release.py` enforces this in the `commit-msg` hook and
in CI.

Changelog items are written for the user, because the app shows them under
"What's new" after an update: what they notice and why it matters, in the app's own
words, without file paths, class names or ticket numbers.

## Checks

GitHub Actions runs [the full check](../../README.md#development) and the release
check on every push to `main`. When both pass and `CHANGELOG.md` names a version
above the last tag, CI tags the commit `vX.Y.Z` and publishes a GitHub Release
whose text is the changelog sections since the last tag, with the release APK signed in CI
([Installing on a phone](../../README.md#installing-on-a-phone)). A red `main` is repaired
straight away; the next green push releases everything since the last tag.

## Prototype branches

Code worth trying out without committing to it may go on a `prototype/<name>`
branch: pushed, never merged, and free of recordings. Research results always
stand in full on their ticket. The branch is deleted once its question is
answered, at the latest when the wayfinder map that owns its ticket closes.
