# Architecture

Sleep Talk Recorder is one Gradle module, `:app`, written in Kotlin with Jetpack Compose. Words in
*italics* are defined in [`GLOSSARY.md`](GLOSSARY.md).

## Packages

Everything lives under `me.mupu.sleeptalkrecorder`:

| Package | Holds | Android types |
|---|---|---|
| `time` | The time model: *night* and *nap* naming, the timeline of a *recording* | none |
| `recording` | The recording session, WAV chunk writer, microphone, foreground service and its notification | only in `MicrophoneSource`, `Recorder`, `RecordingService`, `RecordingNotifications` |
| `data` | Room database, entities and the `SleepRepository` | Room |
| `playback` | The `SleepTimeline` of a sleep's chunk files and the `SleepPlayer` that plays it | only in `SleepPlayer` (Media3) |
| `settings` | The user's settings and the `SettingsStore` that keeps them; the app language | DataStore; AppCompat only in `AppLanguages` |
| `ui` | Navigation, one sub-package per screen (`home`, `library`, `sleep`, `settings`, `setup`), shared `components`, `format` and `theme` | Compose |

The root package holds `MainActivity`, the only activity, and `SleepTalkApplication` with the
`AppContainer`, which wires the shared objects by hand: the database, the repository, the
`SettingsStore` and the `Recorder`. Logic that can be tested without a device (`time`, the WAV
writer, the recording session, level computation, the sleep timeline, the settings) is plain Kotlin
behind small interfaces (`PcmSource`, `RecordingStore`, `Clocks`) and is unit-tested on the JVM.

Screens follow unidirectional data flow: a `ViewModel` combines repository and recorder flows into
one immutable UI state, and the screen sends user actions back as calls.

## Settings

`Settings` declares every setting as a typed `Setting` with its storage key and default; a new
setting is one declaration there. `SettingsStore` keeps them in one Preferences DataStore file and
publishes each as a flow. A stored value the app no longer accepts reads as the default or the
nearest allowed value, and a damaged or unreadable file gives the defaults instead of an error.

`MainActivity` reads the theme, the time format and whether the language question was answered
before it draws anything, so no screen appears in the wrong theme first. The theme is applied in
Compose and to the system bars; the time format reaches every screen as the `ClockFormatter` in
`LocalClockFormatter`, which writes every clock time the app shows, independent of the phone's own
format and the app language.

The app language is the per-app language Android keeps itself, not a DataStore setting: from
Android 13 on the system stores it and offers it in the app's system settings (the build generates
the list of languages from the translated `values-*` folders), and on Android 12 AppCompat stores
it and applies it to `MainActivity`, which is therefore an `AppCompatActivity`. The first start
asks for it with the phone's language selected.

## Recording

A recording runs in `RecordingService`, a foreground service of type `microphone`. Android gives a
microphone service access only when it is started while the app is visible or from a notification,
so `Recorder.start()` is only called from the UI, the service is not sticky (a system restart would
record silence), and a missing permission is reported before the service starts. The notification
shows the *sleep*'s name, a chronometer and a Stop action. A partial wake lock covers the write path.

`RecordingSession` reads 100 ms buffers from `MicrophoneSource` (`AudioRecord`, source
`VOICE_RECOGNITION`, so automatic gain control and noise reduction are off, 16 kHz mono 16-bit) and
hands each buffer to `ChunkedWavWriter`. Stopping is coroutine cancellation; the session finishes
its files and the database rows before the cancellation completes. A microphone that fails or a
storage error ends the session with a reason the home screen shows, never with a crash.

## Storage

Audio of recording `n` is stored in `noBackupFilesDir/recordings/n/` as `0001.wav`, `0002.wav`,
... Each chunk file holds 15 minutes, so a damaged file loses little and no file approaches the
4 GiB WAV limit. Every 10 seconds the writer patches the size fields of the open file's header and
syncs it, and the repository stores the sample count reached; a killed process therefore leaves
playable files that miss at most the last seconds. On the next process start, recordings still
marked as recording are finished with that stored count.

Room (`SleepDatabase`) holds two tables:

- `sleep`: start and end instant; the end is null while the sleep is recorded.
- `recording`: its sleep, the UTC instant of its first sample, the sample rate, the sample count
  and whether it is still recorded.

The schema is exported to `app/schemas/`. Data derived from a sleep or a recording goes into
tables of its own that reference it by foreign key, so these two tables stay stable. Auto backup is off: recordings never leave the phone, and a restored database without its audio would
list sleeps that cannot be played.

## Playback

*Full playback* plays a sleep's audio as one continuous axis: its recordings back to back, each as
its chunk files in order. `SleepRepository.timeline` reads the files of every recording into a
`SleepTimeline`: each file counts with the samples its header declares, but no more than the
database says the recording holds, so audio written after the last flush of a killed recording is
left out; a missing or damaged file ends its recording there.

`SleepPlayer` hands the files to a Media3 ExoPlayer as one playlist item each. All files share one
PCM format without encoder delay, so ExoPlayer moves from file to file on the same audio track,
without a gap. Speed changes keep the pitch. The player belongs to the sleep screen's `ViewModel`
and pauses when the screen is no longer visible, because there is no notification to control it.

## Time model

All instants are stored in UTC and shown in the device's current zone.

The audio sample clock is the time axis of a recording. `RecordingClock` maps a position, a sample
count, to the instant it was recorded: the recording's start plus samples divided by the sample
rate. The start is taken once from the audio clock: `AudioRecord.getTimestamp` reports when a
given sample was captured on the elapsed-since-boot clock, which is converted to the wall clock at
that moment. Wall-clock differences are never used for positions, so setting the clock or an NTP
correction during the night does not move them.

A position in a sleep is the time into its audio. The gap between two recordings is never played
and takes up no position; `SleepTimeline` maps a position to the recording that holds it and,
through that recording's `RecordingClock`, to its instant.

`SleepDate` names a sleep by the local time of its start: 18:00 to 11:59 is a night named after
the evening it belongs to, 12:00 to 17:59 a nap of that day. Because names follow the device's zone,
a sleep is named as the user sees its times.
