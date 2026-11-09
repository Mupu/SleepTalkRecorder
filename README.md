# Sleep Talk Recorder

A private Android app that records the whole night and lets you hear only the parts where
something was said. You start a recording before you sleep; the app keeps recording in the
background with the screen off. In the morning each night is listed, and its player plays either
the whole night or a cut version that skips the silence. Every position is shown twice: as time
into the recording (6:10:00) and as the real time it happened (07:38).

Recordings never leave the phone: there is no account, no network access and no cloud.

## Status

Nothing but an empty app yet. Planning, research and the decisions so far live in the
[GitHub issues](https://github.com/Mupu/SleepTalkRecorder/issues), starting with the
wayfinder map.

## Development

Tooling: JDK 21 (Microsoft Build of OpenJDK) as `JAVA_HOME`, Android Studio with the Android SDK
as `ANDROID_HOME`, and Python 3 for the release check. The emulator needs hardware acceleration
(on Windows WHPX; `emulator -accel-check` tells whether it is usable). The development AVD is a
Pixel profile with an API 36 Google APIs x86_64 image:

```sh
emulator -avd SleepTalk_API36
./gradlew installDebug
adb shell am start -n me.mupu.sleeptalkrecorder/.MainActivity
```

```sh
git config core.hooksPath .githooks   # once per clone: commit-msg hook (attribution + release check)
```

The **full check**, run from the repository root before pushing:

```sh
./gradlew check assembleDebug
python tools/check_release.py
```

It passes when the app builds, lint reports no errors, every unit test passes, and
`CHANGELOG.md` carries the version the commits since the last tag call for. CI runs it on every
push to `main` and tags the release ([Git policy](docs/agents/git.md#versions-and-releases)).

## Installing on a phone

The app is not on Google Play; it is installed directly:

- Over USB or wireless debugging with Android Studio's Run, or `adb install -r <apk>`.
- As a signed APK copied to the phone (allow "Install unknown apps" for the file manager).

An update keeps the recordings only when it is signed with the same key as the installed app, so
the release key is created once and backed up outside the repository.

## Repository guide

- [`AGENTS.md`](AGENTS.md): working rules for everyone, human or agent.
- [`docs/standards/`](docs/standards/code.md): how code is written and reviewed.
- [`docs/agents/`](docs/agents/git.md): Git policy, releases, issue tracker and documentation rules.
- [`CHANGELOG.md`](CHANGELOG.md): what changed for users; the app shows it under "What's new".
