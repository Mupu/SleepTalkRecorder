# Sleep Talk Recorder

A private Android app that records the whole night and lets you hear only the parts where
something was said. You start a recording before you sleep; the app keeps recording in the
background with the screen off. In the morning each night is listed, and its player plays either
the whole night or a cut version that skips the silence. Every position is shown twice: as time
into the recording (6:10:00) and as the real time it happened (07:38).

Recordings never leave the phone: there is no account and no cloud. Speech is recognised on the
phone itself; the app uses the internet only to download its speech recognition models.

## Architecture

One Android module in Kotlin and Jetpack Compose. A foreground service records the microphone into
15-minute WAV files in the app's private storage; a Room database keeps each sleep and its
recordings with the UTC instant of their first sample, and every position is derived from sample
counts. While recording, a compact loudness feature track is written next to the audio; right
after the stop the app finds the sleep's events on it against a rolling baseline and stores them,
like every later result, as annotations with their origin. [`ARCHITECTURE.md`](ARCHITECTURE.md)
describes the packages, the recording, storage and detection design and the time model. Planning, research and decisions live in the
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

The first build downloads the speech recognition runtime sherpa-onnx, which is published only as an
AAR on its GitHub releases, into the Gradle user home and fails if its SHA-256 differs from the one
pinned in `app/build.gradle.kts`.

It passes when the app builds, lint reports no errors, every unit test passes, and
`CHANGELOG.md` carries the version the commits since the last tag call for. CI runs it on every
push to `main` and tags the release ([Git policy](docs/agents/git.md#versions-and-releases)).

### Detector evaluation

`DetectorEvaluationTest` measures the event detector on the labelled test nights under
`.local/testdata/nights/` (local only, described in `.local/testdata/README.md`). It is skipped
unless the Gradle property `detectorEvaluation` names the test data directory, so the full check
and CI never run it; with the property set and no nights there, it fails.

```sh
./gradlew :app:testDebugUnitTest --tests '*DetectorEvaluationTest' -PdetectorEvaluation=.local/testdata
```

For each night and each parameter set the test compares (the defaults and alternatives that each
move one knob), it prints a table and writes `.local/test-output/detector/detector-evaluation.csv`:

- the share of speech-like labels (`speech`, `speech_quiet`, `whisper`, `sleeptalk`,
  `sleeptalk_real`) an event found, in total and per kind;
- false events: events that match no label of any kind;
- how many minutes cut playback of the events takes, with the default lead-in and tail.

An event and a label match when they overlap or lie at most 1 s apart. False events include real
sounds the labels do not cover, such as the distant speech in the DEMAND room tone and the speed
changes of the looped fan recording. The first run extracts the feature tracks of all nights
(one to two minutes) and keeps them in `.local/test-output/detector/tracks/`; later runs take seconds.

### Transcription evaluation

`TranscriptionEvaluationTest` runs the app's speech recognition (detected events, their clips, the
Parakeet model and the transcript filter) over a sample of the events of the test nights and
reports how often words are found where nothing was said. It is opt-in like the detector
evaluation: it runs only when the Gradle property `transcriptionEvaluation` names the test data
directory and `transcriptionModel` the directory holding Parakeet's `encoder.int8.onnx`,
`decoder.int8.onnx`, `joiner.int8.onnx` and `tokens.txt`. On the computer the model runs with
sherpa-onnx's desktop library of the same release, which the build downloads into the Gradle user
home and checks against a pinned SHA-256.

```sh
./gradlew :app:testDebugUnitTest --tests '*TranscriptionEvaluationTest' \
  -PtranscriptionEvaluation=.local/testdata -PtranscriptionModel=<model directory>
```

Each night gives at most 8 events that overlap speech, 4 that overlap a vocal sound
(`sleep_vocal`, `night_terror`) and 20 others, spread evenly over the night, and the run stops
sampling after three minutes. It prints per night how many of each group got words, a murmur or
nothing, lists the words found on non-speech and on speech, and writes every result to
`.local/test-output/transcription/transcription-evaluation.csv`. It reuses the feature tracks the
detector evaluation keeps in `.local/test-output/detector/tracks/` and extracts missing ones.

## Installing on a phone

The app is not on Google Play; a release build signed with your own key is installed directly.
An update keeps the recordings only when it is signed with the same key as the installed app.
Android refuses an update signed with another key, and the only way past that is to uninstall,
which deletes every recording. So the release key is created once, never replaced and backed up
outside the repository. The debug key is no substitute: it is generated per machine.

### Create and back up the release key (once)

Create the keystore outside the repository, for example in your user folder:

```sh
keytool -genkeypair -v -keystore ~/sleeptalk-release.jks -alias sleeptalk \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` comes with the JDK (`$JAVA_HOME/bin`). Choose a strong password; the same one can serve
as store and key password. Then copy the `.jks` file and its passwords to two offline places,
such as a password manager and a USB stick. Whoever has the file and the passwords can sign
updates; without them no update can be installed over the app.

Point the build to the key with a `keystore.properties` file at the repository root. Git ignores
it, so the passwords never reach a commit:

```properties
storeFile=C:/Users/<you>/sleeptalk-release.jks
storePassword=<store password>
keyAlias=sleeptalk
keyPassword=<key password>
```

A relative `storeFile` is resolved against the repository root. Instead of the file, the
environment variables `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` work too. Without them the release build still succeeds but is unsigned
(`app-release-unsigned.apk`, which cannot be installed) and says so at the end of the build;
debug builds never need the key.

### Build and install

```sh
./gradlew assembleRelease   # .local/build/app/outputs/apk/release/app-release.apk
```

On the phone, enable Developer options (tap the build number seven times), then connect it:

- **USB:** enable USB debugging and plug in the cable; `adb devices` lists the phone.
- **Wireless debugging (Android 11+, same network as the PC):** enable Wireless debugging, tap
  "Pair device with pairing code", run `adb pair <ip>:<pairing port>` with the code shown, then
  `adb connect <ip>:<port>` with the address on the Wireless debugging screen. Android Studio's
  "Pair devices using Wi-Fi" does the same with a QR code.

Install, or update over the installed release while keeping its recordings:

```sh
adb install -r .local/build/app/outputs/apk/release/app-release.apk
```

Android Studio's Run does the same once the `release` build variant is selected. An update needs
the same key and a version at least as high as the installed one, which `CHANGELOG.md` provides.

The same signed APK can also be copied to a phone and opened there; Android then asks to allow
"Install unknown apps" for the file manager.

## Repository guide

- [`AGENTS.md`](AGENTS.md): working rules for everyone, human or agent.
- [`ARCHITECTURE.md`](ARCHITECTURE.md): how the app is built.
- [`docs/standards/`](docs/standards/code.md): how code is written and reviewed.
- [`docs/agents/`](docs/agents/git.md): Git policy, releases, issue tracker and documentation rules.
- [`CHANGELOG.md`](CHANGELOG.md): what changed for users; the app shows it under "What's new".
