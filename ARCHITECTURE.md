# Architecture

Sleep Talk Recorder is one Gradle module, `:app`, written in Kotlin with Jetpack Compose. Words in
*italics* are defined in [`GLOSSARY.md`](GLOSSARY.md).

## Packages

Everything lives under `me.mupu.sleeptalkrecorder`:

| Package | Holds | Android types |
|---|---|---|
| `time` | The time model: *night* and *nap* naming, the timeline of a *recording*, the latest stop time | none |
| `recording` | The recording session, WAV chunk writer, microphone, foreground service and its notifications | only in `MicrophoneSource`, `Recorder`, `RecordingService`, `RecordingNotifications` |
| `detection` | The feature track (extraction and file format) and the `EventDetector` with its baseline | none |
| `data` | Room database, entities, the `SleepRepository`, and loading feature tracks for a sleep | Room |
| `playback` | The `SleepTimeline` of a sleep's chunk files, the `SleepAudio` stream made of them, the `CutPlan` of cut playback and the `SleepPlayer` that plays it | only in `SleepPlayer` (Media3) |
| `retention` | Automatic deletion: which sleeps are old enough (`SleepRetention`) and the WorkManager job that deletes them | only in `AutoDeletion` (WorkManager) |
| `settings` | The user's settings and the `SettingsStore` that keeps them; the app language | DataStore; AppCompat only in `AppLanguages` |
| `models` | The transcription models: the catalogue of downloadable models (`TranscriptionModel`), the `ModelInstaller` that downloads and checks their files, the `ModelLibrary` with their states and the active one, and the WorkManager job that downloads them | only in `ModelDownloadWorker` (WorkManager) |
| `readiness` | Whether the phone lets a whole night be recorded: the `Requirement`s it misses, the `VendorStep`s of its maker, and the `SystemPage`s that fix them | only in `PhoneReadiness` |
| `ui` | Navigation, one sub-package per screen (`home`, `library`, `sleep`, `settings`, `setup`), shared `components`, `format` and `theme` | Compose |

The root package holds `MainActivity`, the only activity, and `SleepTalkApplication` with the
`AppContainer`, which wires the shared objects by hand: the database, the repository, the
`SettingsStore`, the `Recorder` and the `ModelLibrary`; it also keeps automatic deletion scheduled
and starts the search for events of finished sleeps. Logic that can be tested without a device
(`time`, `detection`, the WAV writer, the recording session, level computation, the sleep
timeline and its audio stream, the cut plan, the timeline view of the sleep screen, the
sensitivity sheet's re-scan, the settings,
which sleeps automatic deletion removes, the model downloads and states) is plain Kotlin behind
small interfaces (`PcmSource`, `RecordingStore`, `Clocks`, `ByteSource`, `ModelDownloads`) and is
unit-tested on the JVM.

Screens follow unidirectional data flow: a `ViewModel` combines repository and recorder flows into
one immutable UI state, and the screen sends user actions back as calls.

## Settings

`Settings` declares every setting as a typed `Setting` with its storage key and default; a new
setting is one declaration there. `SettingsStore` keeps them in one Preferences DataStore file and
publishes each as a flow. A stored value the app no longer accepts reads as the default or the
nearest allowed value, and a damaged or unreadable file gives the defaults instead of an error.

`MainActivity` reads the theme, the time format and the first-start setup step before it draws
anything, so no screen appears in the wrong theme first. The theme is applied in
Compose and to the system bars; the time format reaches every screen as the `ClockFormatter` in
`LocalClockFormatter`, which writes every clock time the app shows, independent of the phone's own
format and the app language. Time settings are picked in Android's own time picker dialog, opened
with the user's time format and the dialog theme that matches the app's light or dark colors.

The first-start setup is a sequence of `SetupStep`s (the language, the permissions page, then the
speech recognition model), each
finished once with a setting of its own; `MainActivity` shows the first unfinished step instead of
the app, so a setup left halfway continues where it stopped.

The app language is the per-app language Android keeps itself, not a DataStore setting: from
Android 13 on the system stores it and offers it in the app's system settings (the build generates
the list of languages from the translated `values-*` folders), and on Android 12 AppCompat stores
it and applies it to `MainActivity`, which is therefore an `AppCompatActivity`. The first start
asks for it with the phone's language selected.

## Readiness

`PhoneReadiness` checks the microphone permission, whether notifications are on and whether the app
is exempt from battery optimisation. `MainActivity` re-checks whenever it comes to the front, also
on return from a system dialog or settings page, and home warns while one is missing. The
permissions page opens the system's dialog for each; once Android no longer shows a permission's
dialog (after the second refusal) it opens the app's settings page instead. The battery exemption
uses the direct request dialog, which Google Play restricts but this directly installed app may use.

Some makers end background apps on their own (Samsung, Xiaomi, MediaTek's DuraSpeed). For them the
page offers `VendorStep`s, each a list of pages tried in order: the vendor's own page, then the
app's battery or info page, so a vendor page that is missing or closed to other apps never strands
the user. Whether such a setting took effect cannot be read back, so vendor steps are offered but
never counted as missing.

## Recording

A recording runs in `RecordingService`, a foreground service of type `microphone`. Android gives a
microphone service access only when it is started while the app is visible or from a notification,
so `Recorder.start()` is only called from the UI, the service is not sticky (a system restart would
record silence), and a missing permission is reported before the service starts. The notification
shows the *sleep*'s name, a chronometer and a Stop action. A partial wake lock covers the write path.

`RecordingSession` reads 100 ms buffers from `MicrophoneSource` (`AudioRecord`, source
`VOICE_RECOGNITION`, so automatic gain control and noise reduction are off, 16 kHz mono 16-bit) and
hands each buffer to `ChunkedWavWriter` and to the recording's feature track (see
[Finding events](#finding-events)). Stopping is coroutine cancellation; the session finishes
its files and the database rows before the cancellation completes. A microphone that fails or a
storage error ends the session with a reason the home screen shows, never with a crash.

A call, another app or the microphone privacy toggle can take the microphone away during the
night. Android then keeps the recording running but delivers silence and says so through the
recording callback (`AudioRecordingConfiguration.isClientSilenced`), which `MicrophoneSource`
registers before it starts. The session asks after every buffer and stores each silenced stretch
as a `MICROPHONE_UNAVAILABLE` annotation, saved again at every flush while it lasts, so a killed
process keeps it.

A new recording continues the last sleep when it starts in the same sleep window, the stretch of
the clock that names the sleep (a night from 18:00 to 11:59, a nap from 12:00 to 17:59), and after
that sleep's end; otherwise it starts a new sleep. A continued sleep is running again, and its
events are found anew once the recording ends.

Unless the user switched it off, a recording also stops on its own at the latest stop time of the
settings: the first moment after the start at which the device's clock shows that time, or after
the moment the setting was changed during the recording. The session compares the wall clock with
it before every buffer, so no alarm is needed while the service runs and holds its wake lock, and a
clock that is set or corrected stops the recording at the time it then shows. The service then
leaves a notification on a channel of its own that says why the recording stopped.

## Storage

Transcription models are stored in `noBackupFilesDir/models/` ([Transcription models](#transcription-models)).
Audio of recording `n` is stored in `noBackupFilesDir/recordings/n/` as `0001.wav`, `0002.wav`,
... Each chunk file holds 15 minutes, so a damaged file loses little and no file approaches the
4 GiB WAV limit. Every 10 seconds the writer patches the size fields of the open file's header and
syncs it, and the repository stores the sample count reached; a killed process therefore leaves
playable files that miss at most the last seconds. On the next process start, recordings still
marked as recording, cut off by a crash, a kill or a dead battery, are repaired: each chunk file's
header is rewritten to declare every whole sample the file holds (which keeps audio written after
the last flush), a file too short for a header is removed, and the recording is finished with the
samples of its files. Only if the files cannot be repaired does the stored count stand.

Next to the chunk files lies `features.bin`, the recording's feature track (see
[Finding events](#finding-events)). It is flushed before the WAV files at every flush, so it always
covers the samples the database counts as saved.

Room (`SleepDatabase`) holds four tables:

- `sleep`: start and end instant; the end is null while the sleep is recorded.
- `recording`: its sleep, the UTC instant of its first sample, the sample rate, the sample count
  and whether it is still recorded.
- `event_kind`: the kinds of *event*, as data rather than a list in code: an id that annotations
  refer to, the user's name (null for the kinds the app ships, which it names in the app
  language), a colour (a theme colour role such as `primary`, so it follows light and dark mode,
  or a fixed `#RRGGBB`), and whether the sleep screen offers a filter for the kind. The database
  adds the app's kinds that are missing each time it opens, so a new app version can ship new
  kinds; v1 ships only the plain event.
- `annotation`: every result about a stretch of a sleep, with its origin. The stretch is given in
  milliseconds of the sleep's audio (the playing axis, see [Time model](#time-model)), so it does
  not depend on the detector's hop size. The `type` says what the annotation states (`EVENT` with
  a kind, `SENSITIVITY` with a start threshold in `value`: the one the detector chose for the
  sleep, or one the user set for the stretch,
  `MICROPHONE_UNAVAILABLE` from the recorder), and `origin` (`DEVICE`, `SERVER` or `USER`),
  `producer` and `producer_version` say who stated it. Gaps between recordings take no time on
  this axis and need no annotation: they follow from the recordings' start instants and lengths.

Annotations are layers: an analysis that runs again replaces only its own annotations (same
origin and producer) and leaves every other layer alone. Hiding an event, a sensitivity override
for a stretch, a transcript or a user's correction is one more annotation type beside the
detector's, and user annotations win over every analysis; none of them needs a new table.

The schema is exported to `app/schemas/`. Data derived from a sleep or a recording goes into
tables of its own that reference it by foreign key, so the `sleep` and `recording` tables stay
stable. Auto backup is off: recordings never leave the phone, and a restored database without its audio would
list sleeps that cannot be played.

## Deletion

`SleepRepository.deleteSleep` is the only way a sleep is removed, by hand or automatically, and it
removes everything the sleep owns: first the directory of each of its recordings, then its row.
The rows of other tables go with it through `ON DELETE CASCADE`. So every table holding data of a
sleep or a recording references it that way, and every file of a recording lives in the
recording's directory; then deletion needs no change when data is added. The files go first so
that an interrupted deletion never leaves files without a row: the sleep stays listed and can be
deleted again. A sleep that is still recorded is never deleted.

In the library a long press on a finished sleep asks for confirmation and names the size of its
files. With "delete sleeps automatically" on, WorkManager runs `AutoDeleteWorker` once a day, and
once more whenever the setting or its days change. It deletes every sleep whose date, the day it
is named after, lies more than the chosen number of calendar days before today: a sleep kept for
30 days is still there on the 30th day after its date and gone on the 31st, whatever time of day
the work runs and across daylight saving changes.

## Finding events

Events are found from a compact *feature track*, never from the audio, so finding them again takes
a fraction of a second even for a long night. `FeatureExtractor` turns the PCM of a recording into
one 8-byte record per hop of 320 samples (20 ms): full-band RMS and peak level, the levels of the
bands below 300 Hz, from 300 to 3400 Hz (the speech band) and above, spectral flatness of the
speech band, zero crossings and voicing. Levels are dBFS in steps of 0.5 dB. `RecordingSession`
extracts it live from the buffers it records, which costs little next to keeping the microphone
open, and `FeatureTrackFile` stores it as an append-only file whose header names its format
version. Only the speech band is used to find events yet; the other columns are there so later
kinds of events can be told apart without reading the audio again.

When a track is missing, of an older format version, or shorter than its audio, the repository
extracts it again from the chunk files before using it. That takes seconds per hour of audio but
happens only after a storage failure or a change of the format.

`EventDetector` works on the tracks of a sleep's recordings back to back (`SleepTrack`, which maps
each hop to its exact position in the sleep):

1. The level is the speech band, averaged in power over 100 ms, so rumble and hiss barely move it.
2. The *baseline* is the 20th percentile of the speech band over a minute centred on each moment,
   kept in a sliding histogram of the 256 level codes. It stays below sounds that fill less than
   most of that minute and follows a floor that changes (a fan switching on) within half a
   minute. Digital silence is left out.
3. The *sensitivity* is chosen per sleep: the start threshold above the baseline is the median of
   the residual (level minus baseline) plus a multiple of the noise spread, the distance from the
   residual's 16th percentile to its median, held between 5 and 25 dB. Events lie in the upper
   tail and do not move it, so a noisy night gets a higher threshold than a quiet one. Where the
   user set a sensitivity, their threshold replaces it (see [Sensitivity](#sensitivity)).
4. A hysteresis state machine starts an event after 60 ms above the start threshold and ends it
   after 300 ms below a stop threshold 40 % lower. Events shorter than the minimum length are
   dropped, and events less than 1.5 s apart are joined.

Every knob is a named parameter of `DetectionParams`; one sensitivity setting from 0 to 1 sets the
noise-spread multiple and the minimum length together.

`SleepRepository.findEvents` finds them with the sensitivities the user set and stores the events
and the chosen threshold as annotations of the producer `loudness`, whose version combines the
detector's and the feature format's versions. It only stores them while the sleep is still
finished as it was when the search began, so a sleep continued by a new recording meanwhile is
searched again after that recording. Searches run one at a time, so the last one stored always
read the latest settings.
`AppContainer` searches every finished sleep without annotations of the current version: after
each recording ends and once at process start, which also covers a recording a killed process left
behind and a detector that changed with an update.

The sleep screen reads the same track to draw the timeline: the smoothed speech-band level, grey
except where an event lies, with the baseline as a dashed line. The timeline runs on the clock axis
(see [Time model](#time-model)), so gaps between recordings take their real width; they and the
stretches in which the microphone was unavailable are hatched. `SleepLoudness` keeps maxima of
16, 256 and 4 096 hops, so every column of the timeline reads only a few values at any zoom.

### Sensitivity

The automatic sensitivity can be overridden for one sleep, from the sleep screen's menu, or for
the stretch from 30 s before to 30 s after an event, from the event's menu. Each override is a
`SENSITIVITY` annotation of the user: a stretch of the sleep's audio with a start threshold above
the baseline. It names positions, not hops or events, so every later search uses it again, also
one by a changed detector, and re-scans never remove it, since they replace only the detector's
own annotations. Where overrides overlap, the newer one counts; a new one replaces those that lie
within its stretch, so the sleep's own setting replaces all others, and a reset takes back those
of its stretch.

The sensitivity sheet shows its stretch's level, baseline and threshold with the events found,
and how many there are now and were before. The level, baseline and automatic threshold do not
depend on the threshold, so moving the slider runs only the detector's state machine again
(`EventDetector.rescan`) over the stretch and 30 s on either side: milliseconds even for a whole
night, cancelled by the next move. Applying stores the override and searches the whole sleep
again, so the stored events are exactly those a later search finds.

## Transcription models

Speech is recognised on the phone with sherpa-onnx, whose native libraries are the only part of
the speech recognition the APK carries; release builds keep only the arm64 libraries. The models
themselves (Parakeet TDT 0.6B Ultra by default, Whisper small as an alternative) are downloaded
on demand into `noBackupFilesDir/models/<model>/`, never bundled.

`TranscriptionModel` lists each model's files with their size and SHA-256, downloaded as single
files from the Hugging Face repository the sherpa-onnx project publishes the model in, pinned to
one commit. Single files need no decompressor and no room for an archive beside its contents, and
the pinned commit and checksums keep a file changed upstream from ever being installed.
`ModelInstaller` writes each file as `<name>.part`, continues a partial file with an HTTP range
request, and renames it only once its checksum matched, so a file under its own name is always
complete and checked, and a model is installed when all its files are. A wrong checksum deletes
the file and fails the download; it is not retried on its own, because a changed file would fail
again.

Each model downloads in a unique WorkManager job that waits for a network connection and runs as
a foreground service of type `dataSync` with a progress notification. A lost connection retries
the job with back-off, continuing where the files stopped; cancelling a running download deletes
what it downloaded.
`ModelLibrary` combines the jobs' progress with the files on disk into each model's state. The
active model is a setting that only ever names an installed model, unless none is installed: the
first model installed becomes active, and deleting the active model hands over to another
installed one. The transcript language is shown only while the active model needs one.

## Playback

*Full playback* plays a sleep's audio as one continuous axis: its recordings back to back, each as
its chunk files in order. `SleepRepository.timeline` reads the files of every recording into a
`SleepTimeline`: each file counts with the samples its header declares, but no more than the
database says the recording holds, so audio written after the last flush of a killed recording is
left out; a missing or damaged file ends its recording there.

`SleepPlayer` plays the timeline as one stream, `SleepAudio`: an RF64 header (WAV with 64-bit
sizes, so a sleep of any length fits) followed by the samples of every chunk file the timeline
plays, served to a Media3 ExoPlayer by a data source of its own. All recordings share the
microphone's PCM format, so the stream is one audio track without gaps, and a position in it is
the position in the sleep down to the sample. Speed changes keep the pitch. The player belongs to
the sleep screen's `ViewModel` and pauses when the screen is no longer visible, because there is
no notification to control it.

An event is played on its own as a stretch from the *lead-in* before it to the tail after it, both
from the settings; the player pauses itself at the stretch's end. Lead-in and tail stop at the
start of a recording and at a stretch in which the microphone was unavailable
(`SleepTimeline.uninterrupted`), so neither the audio before a gap nor the silence of a taken
microphone is played around an event.

*Cut playback*, the default, plays the events the list shows in this way, one after the other.
`CutPlan` joins the stretches of events that overlap, so close events play without a jump, and
keeps stretches apart that only touch where a recording starts. While playing, the player jumps
from the end of a stretch to 200 ms before the next (ExoPlayer starts a seeked WAV stream at its
next 100 ms block of samples) and stops after the last. The jumps are audible but never click:
an audio processor in ExoPlayer's output path, `CutFader`, knows the stream position of every
sample from the position ExoPlayer reports after each flush, fades each stretch in and out over
15 ms and silences everything between stretches, so the fades sit at the stretch edges whenever
the jump itself happens. It also fades in after every seek, in full playback too.

The sleep screen highlights the event at the playhead in the list and scrolls it into view, also
when the playhead lands there by a tap or a drag on the timeline; the event counts from the start
of its lead-in to the end of its tail as cut playback plays them. Auto zoom, on when the screen
opens, frames that event on the timeline with four times its length (30 s to 2 min) and keeps the
playhead in view while playing; panning or zooming by hand, also with the overview, switches it
off, its button on again.

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

To show the gaps, `SleepTimeline` also has a clock axis: the time since the sleep's first sample,
on which each recording starts at its own instant and each gap takes its real time. A moment in a
gap plays from the start of the next recording. A recording that starts before the previous one
ended on the clock, because the clock was set back, follows it there without a gap, so the clock
axis keeps the order of the audio.

`SleepDate` names a sleep by the local time of its start: 18:00 to 11:59 is a night named after
the evening it belongs to, 12:00 to 17:59 a nap of that day. Because names follow the device's zone,
a sleep is named as the user sees its times.
