# Code standards

Everything permanent in this repository is written like the code of a
production-ready app. Prototype branches favour fast results; for them these rules
are guidance.

## Readable code

Write code for a human reader. Group related statements and separate steps with
blank lines instead of packing them together. Comment what the code cannot show on
its own: units (samples, frames, milliseconds, dBFS), invariants, platform quirks
and the design decision behind a structure, that is, why it exists rather than
what the next line does.

## Finished code

Committed code contains no TODO markers, commented-out code or dead code; open
work is an issue. Public declarations of a module are documented with KDoc where
they are declared.

Input from files, the microphone, the system or the user never crashes the app.
A missing permission, a full disk, a killed service or a damaged recording leads
to a state the app shows and can recover from, never to an uncaught exception.

Until the first beta release (0.1.0), a changed design replaced the old one
directly. Since then real nights are recorded with released versions, so
recordings and their metadata survive every update: every schema change gets a
Room migration (an auto-migration where it fits, a manual one otherwise) with a
test that migrates from the previous schema exported in `app/schemas/`, never a
destructive fallback. No dual paths or fallbacks exist only to keep unreleased
development state working.

Keep implementation private to its feature until actual reuse justifies
extraction; no speculative shared abstractions.

## Kotlin and Android

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
  and the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide);
  four spaces, as `.editorconfig` sets.
- UI is Jetpack Compose with Material 3; state flows down, events flow up.
- Long-running work (recording, analysis, transcoding) runs off the main thread
  in coroutines with structured concurrency; nothing is launched in `GlobalScope`.
- Signal processing (feature extraction, baseline, event detection, time
  mapping) is plain Kotlin without Android types, so it is unit-tested on the JVM.
- New libraries go into `gradle/libs.versions.toml`; a new dependency needs owner
  approval ([AGENTS.md](../../AGENTS.md)).

## Tests

- Normal tests run from a fresh clone after the documented tooling setup, without
  the owner's recordings. Keep fixtures small and redistributable, or generate
  the signal in the test (sine, noise, bursts at known times), so their expected
  behaviour is clear from tracked files.
- Tests on real recordings or the local test nights under `.local/testdata/` are
  opt-in: they are skipped by default and never run in CI. When such a test is
  requested and its input is missing, it fails instead of passing silently.
- Never copy recordings into Git to make a fixture available; generated test
  output stays under `.local/test-output/`.
