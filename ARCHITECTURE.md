# Architecture

Sleep Talk Recorder is one Gradle module, `:app`, written in Kotlin with Jetpack Compose. Words in
*italics* are defined in [`GLOSSARY.md`](GLOSSARY.md).

## Packages

Everything lives under `me.mupu.sleeptalkrecorder`:

| Package | Holds | Android types |
|---|---|---|
| `time` | The time model: the *sleep windows* that name *nights* and *naps*, the timeline of a *recording*, the latest stop time | none |
| `recording` | The recording session, WAV chunk writer, microphone, foreground service and its notifications | only in `MicrophoneSource`, `Recorder`, `RecordingService`, `RecordingNotifications` |
| `detection` | The feature track (extraction and file format) and the `EventDetector` with its baseline | none |
| `data` | Room database, entities, the `SleepRepository` through which screens read and change sleeps, `SleepRecordings` (the recording session's `RecordingStore`), `SleepTranscripts` (speech recognition's `TranscriptStore`), the `RecordingFiles` of each recording, loading feature tracks for a sleep, and the `AnalysisExport` of every sleep | Room |
| `playback` | The `SleepTimeline` of a sleep's chunk files, the `SleepAudio` stream made of them, the `CutPlan` of cut playback and the `SleepPlayer` that plays it | only in `SleepPlayer` (Media3) |
| `share` | Sharing events as a short video or an audio file: the stretch that is shared (`ShareStretch`), the caption sent along (`caption`), the video's still `ShareCard`, the `Mp4Encoder` and `EventShares`, which writes the files and hands them to Android's share sheet | only in `ShareCard` (`Bitmap`, `Canvas`), `Mp4Encoder` (`MediaCodec`, `MediaMuxer`) and `EventShares` (`FileProvider`, intents) |
| `retention` | Automatic deletion: which sleeps are old enough (`SleepRetention`) and the WorkManager job that deletes them | only in `AutoDeletion` (WorkManager) |
| `settings` | The user's settings and the `SettingsStore` that keeps them; the app language | DataStore; AppCompat only in `AppLanguages` |
| `models` | The transcription models: the catalogue of downloadable models (`TranscriptionModel`), the `ModelInstaller` that downloads and checks their files, the `ModelLibrary` with their states and the active one, and the WorkManager job that downloads them | only in `ModelDownloadWorker` (WorkManager) |
| `transcription` | Speech recognition of events: the `EventAnalysis` interface, the `SpeechRecognition` with its `TranscriptFilter`, the sherpa-onnx `SherpaTranscriber`, the `TranscriptionRunner` that works through the events, the `TranscriptionPauses` the user made, and the WorkManager jobs that run it | only in `TranscriptionWork` (WorkManager, notifications, battery) |
| `notifications` | The ids of all notifications and the progress notification that model downloads and speech recognition share | Android notifications, WorkManager's `ForegroundInfo` |
| `readiness` | Whether the phone lets a whole night be recorded: the `Requirement`s it misses, the `VendorStep`s of its maker, and the `SystemPage`s that fix them | only in `PhoneReadiness` |
| `whatsnew` | The changelogs bundled into the app: their `Changelog` parser, the `BundledChangelogs` of each app language and `WhatsNew`, which decides whether an update brought news the user has not seen | only in `BundledChangelogs.fromAssets` (assets) |
| `update` | The app's updates from its releases: which release is an update (`Release`), when one is downloaded and installed (`UpdatePolicy`), and the `Updater` that checks, downloads, verifies and installs it | only in `AndroidUpdates` (HTTP, package manager, `PackageInstaller`) |
| `ui` | Navigation, one sub-package per screen (`home`, `library`, `sleep`, `stats`, `settings`, `setup`, `whatsnew`), shared `components`, `format`, `share` (the share step and the selection of events both the sleep screen and the library use), `tags` (the tag filter, the tag window and their dialogs, which both use too) and `theme` | Compose |

The root package holds `MainActivity`, the only activity, and `SleepTalkApplication` with the
`AppContainer`, which wires the shared objects by hand: the database with the repository and stores, the
`SettingsStore`, the `Recorder`, the `ModelLibrary` and the speech recognition; it also keeps
automatic deletion scheduled, starts the search for events of finished sleeps, schedules their
speech recognition and owns the `Updater`. Logic that can be tested without a device
(`time`, `detection`, the WAV writer, the recording session, level computation, the sleep
timeline and its audio stream, the cut plan, the stretches events are shared with and their caption, the selection of events, the timeline view, the tag filter and the tag window's choice, the
sensitivity sheet's re-scan, the numbers of the stats screen, the settings,
which sleeps automatic deletion removes, the model downloads and states, the transcript filter,
the speech recognition run, which transcript each event shows and which results of models it compares, reading the changelogs, and finding,
verifying and timing an update) is plain Kotlin behind small interfaces (`PcmSource`,
`RecordingStore`, `Clocks`, `ByteSource`, `ModelDownloads`, `TranscriptStore`, `PauseStore`, `Transcriber`,
`ReleaseSource`, `ApkInspector`, `PackageInstall`) and
is unit-tested on the JVM.

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
The *sleep windows* are set on a bar of their own over the 24 hours of a day, drawn on a Compose
`Canvas`: a sun thumb where naps start and a moon thumb where nights start, which snap to quarter
hours and cannot pass each other; tapping a thumb or its time opens the same time picker. Both
times are stored as one setting, so they are never half changed.

The first-start setup is a sequence of `SetupStep`s (the language, the permissions page, "Install
unknown apps" for updates, then the speech recognition model), each finished once with a setting
of its own; `MainActivity` shows the first unfinished step instead of the app, so a setup left
halfway continues where it stopped. A build that does not update itself leaves out the updates
step.

The app language is the per-app language Android keeps itself, not a DataStore setting: from
Android 13 on the system stores it and offers it in the app's system settings (the build generates
the list of languages from the translated `values-*` folders), and on Android 12 AppCompat stores
it and applies it to `MainActivity`, which is therefore an `AppCompatActivity`. The first start
asks for it with the phone's language selected.

## What's new

The build copies the changelog of every app language (`CHANGELOG.md` and each `CHANGELOG.<lang>.md`
at the repository root) into the app's assets, so the app shows the same text the releases are made
from and nothing is copied by hand. The app reads the changelog of the language it shows and falls
back to the English one when that is missing or has no release. `Changelog` reads the Markdown
subset the changelogs are written in (release and section headings, paragraphs, lists, emphasis,
strong emphasis and inline code) and leaves anything else as plain text, so no Markdown library is
needed.

`WhatsNew` keeps the version whose news the user has seen as a setting. When the installed version
is higher, a pop-up over the open screen shows the releases since, once: any way of closing it
counts as seen, and one of its buttons opens the list of all releases, which the settings open too.
A fresh install has nothing to announce and only stores its version; a downgrade stores the lower
one, so the next update shows everything after it.

## Updates

The app updates itself from the releases of its GitHub repository, without a store or a server of
its own. Each release carries the APK signed in CI and its `sha256sum` line as assets. Each start
of the app (not a recreation of its screen) and "Check now" in the settings ask GitHub's API for
the latest release, which is never a draft or a pre-release; it is an update when its tag names a
higher version than the installed one and it carries both files. The source is a build setting:
release builds ask GitHub, debug builds, an app of their own that no release APK could update,
ask only a test source given at build time and otherwise do not update themselves
([README](README.md#testing-the-updater)).

The `Updater` downloads the APK into the cache on its own only on an unmetered network; on mobile
data home's update card and the updates section of the settings offer the download with its
size. A download continues where it stopped, and the file gets its final name only once its
SHA-256 matches the published checksum and the package manager reads it as this app, with a
higher version code, signed with exactly the installed app's certificates; anything else is deleted and shown as refused. A verified file is offered again on
every later start, before and whatever the check finds, until it is installed.

Installing kills all of the app's processes, so it never happens during a recording (`UpdatePolicy`),
and home shows nothing about updates while one runs. Once an update is found, the settings show
the same steps and buttons as home's card below its status, in place of "Check now". The Update
button installs at once otherwise; on its own the app installs only when it has been
off the screen for ten seconds and nothing records, plays or recognises speech. The install is a
`PackageInstaller` session that asks for no user action: with "Install unknown apps" granted (a
setup step), `UPDATE_PACKAGES_WITHOUT_USER_ACTION` declared and the app updating itself, Android
installs without a dialog as long as the APK's target API level is recent enough for the phone.
When Android still asks, the system's confirmation is shown for a tap in the app; from the
background it cannot be shown, so the session is given up and the update waits for a tap.

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

A new recording continues the last sleep when the current *sleep windows* give it the same name as
that sleep (the same night or nap) and it starts after that sleep's end; otherwise it starts a new
sleep, named by the windows at that moment. A continued sleep is running again, and its events are
found anew once the recording ends.

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

Room (`SleepDatabase`) holds five tables:

- `sleep`: start and end instant (the end is null while the sleep is recorded) and its name: the
  kind (night or nap) and the date it is named after, given by the sleep windows when it started.
  The library, the stats and automatic deletion read the stored name, so a change of the windows
  renames no earlier sleep.
- `recording`: its sleep, the UTC instant of its first sample, the sample rate, the sample count
  and whether it is still recorded.
- `event_kind`: the kinds of *event*, as data rather than a list in code: an id that annotations
  refer to, the user's name (null for the kinds the app ships, which it names in the app
  language), a colour (a theme colour role such as `primary`, so it follows light and dark mode,
  or a fixed `#RRGGBB`), and whether the tag filter offers the kind as a system tag. The database
  adds the app's kinds that are missing each time it opens, so a new app version can ship new
  kinds; v1 ships the plain event and the speech event (tertiary colour), both offered.
- `tag`: the tags the user sorts events with: Favourite, which the app provides with the fixed id
  1 and names in its language (the database adds it each time it opens if it is missing), and the
  user's own, with a name that is unique whatever its case.
- `annotation`: every result about a stretch of a sleep, with its origin. The stretch is given in
  milliseconds of the sleep's audio (the playing axis, see [Time model](#time-model)), so it does
  not depend on the detector's hop size. The `type` says what the annotation states (`EVENT` with
  a kind, `SENSITIVITY` with a start threshold in `value`: the one the detector chose for the
  sleep, or one the user set for the stretch,
  `MICROPHONE_UNAVAILABLE` from the recorder, `TRANSCRIPT` with the kind speech recognition or
  the user gives an event and its words in `text`, `HIDDEN` from the user over an event they hid, `TAG` from the user over an event they gave the tag `tag_id`, `NAME` from the user with the name they gave an event in `text`), and `origin` (`DEVICE`, `SERVER` or `USER`),
  `producer` and `producer_version` say who stated it. Gaps between recordings take no time on
  this axis and need no annotation: they follow from the recordings' start instants and lengths.

Annotations are layers: an analysis that runs again replaces only its own annotations (same
origin and producer) and leaves every other layer alone. Hiding an event, a sensitivity override
for a stretch, a transcript or a user's correction is one more annotation type beside the
detector's, and user annotations win over every analysis; none of them needs a new table.

An event is hidden while a `HIDDEN` mark overlaps it, rather than only one over the same stretch
as transcripts are matched: a search with other settings or a changed detector finds the event
again with slightly different bounds, and the mark still keeps to it. A mark whose event is no
longer found hides nothing until the event is found again. Hidden events stay in the sleep and
in its speech recognition, but are left out of the counts the library, the sleep screen and the
stats show.

Tags and names follow the same rule: an event has a tag while a `TAG` mark of it overlaps the
event (`SleepEvent.tagIds`), and its name is that of the latest `NAME` mark that overlaps it.
Deleting an own tag deletes its marks through the foreign key. Tags and hiding are independent:
a hidden event keeps its tags. *System tags* are not stored but follow from the event
(`SleepEvent.tags`, `EventTag.System`): named, hidden, each kind the filter offers and murmur;
a new kind of system tag is one more subtype there. Automatic deletion keeps a sleep with an
event that has a tag or a name (`SleepSummary.taggedCount`, hidden events included) while
"Keep tagged sleeps" is on, which it is by default. Version 1 had a `FAVOURITE` mark with the
name in its `text`; the migration to version 2 (`Migration1To2`) turned each into a `TAG` mark of
Favourite and, with a name, a `NAME` mark over the same stretch.

The library's second tab lists the tagged and named events of all sleeps under their sleep,
newest first (`SleepRepository.taggedEvents`), with the same tag filter as the sleep screen, and
opening one opens its sleep at the event, paused, with the hidden events listed if it is hidden,
while its play button also plays the event on its own
and its share button opens the event's share step over the library, so sharing leaves the user
there: `TaggedShare` reads the sleep with a `SleepViewModel` of its own, which it lets go of once
the step closes (see [Sharing an event](#sharing-an-event)). A long press on an event starts
selection mode there as on the sleep screen, and the selection may span several sleeps; its app
bar shares the selected events or opens the tag window for them.
`LibraryViewModel.shareSelected` decides how: a single selected event gets that same step,
several the step of several events, which the library holds itself, over the library too.

Both screens edit tags through a `TagEditing` of their `ViewModel` (package `ui.tags`). Its tag
window shows every assignable tag for one event or the selected ones as on, off or a dash for
some (`TagChoices`); a tap turns empty into ticked and ticked into empty, and a dash into ticked,
after which it never comes back, since it only means "untouched, on some". OK gives the ticked
tags to all events and takes the cleared ones from all, leaving untouched tags as they were. The
changes it applied are stored (`Settings.tagChanges`) and the window opens with them applied to
the tags all its events have or none has (a tag only some have always opens as a dash), so
tagging the next event the same way takes the tag button and OK; it keeps remembering a change
the user left untouched, even where it did not apply. New tags are made, and own tags deleted
after a question that names how many events lose them, from both the tag window and the filter
window; a deleted tag leaves an open tag window and the remembered changes at once.

The schema is exported to `app/schemas/`. Data derived from a sleep or a recording goes into
tables of its own that reference it by foreign key, so the `sleep` and `recording` tables stay
stable. Auto backup is off: the audio lies in `noBackupFilesDir`, which no backup includes, and a
restored database without its audio would list sleeps that cannot be played.

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

"Export for analysis" in the settings (`AnalysisExport`) copies every sleep onto the app's folder
of shared storage, `Android/data/<app>/files/export/`, because a release build's private storage
cannot be read from outside: the database as `sleeps.db` (a `VACUUM INTO` snapshot, made in
private storage and then copied, since SQLite creates it readable by the app alone) and every
recording's directory under `recordings/`. A PC pulls it over USB with adb. A finished recording's
files never change, so a new export copies only the files that are new, grown or newer
(`ExportPlan`) and removes those of sleeps deleted since; the database is copied fresh each time.
The settings show what the recordings take and what an export would still copy, and a progress
bar while it copies. It refuses to start without room for what it copies, and it runs in the
container's scope, so leaving the settings does not stop it.

## Finding events

Events are found from a compact *feature track*, never from the audio, so finding them again takes
a fraction of a second even for a long night. `FeatureExtractor` turns the PCM of a recording into
one 8-byte record per hop of 320 samples (20 ms): full-band RMS and peak level, the levels of the
bands below 300 Hz, from 300 to 3400 Hz (the speech band) and above, spectral flatness of the
speech band, zero crossings and voicing. Levels are dBFS in steps of 0.5 dB. `RecordingSession`
extracts it live from the buffers it records, which costs little next to keeping the microphone
open, and `FeatureTrackFile` stores it as an append-only file whose header names its format
version. Finding events uses only the speech band.

When a track is missing or of an older format version, the repository extracts it again from the
chunk files before using it. That takes about 16 s per hour of audio on the development emulator
(over two minutes for an 8.5-hour night) and happens only after a storage failure or a change of
the format. A track shorter than its audio, as a killed recording leaves it (its audio runs up to
ten seconds past the track's last flush), keeps its hops, and only the hops after them are
extracted and appended.

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
except where an event lies that is not hidden, with the baseline as a dashed line. The timeline runs on the clock axis
(see [Time model](#time-model)), so gaps between recordings take their real width; they and the
stretches in which the microphone was unavailable are hatched. `SleepLoudness` keeps maxima of
16, 256 and 4 096 hops, so every column of the timeline reads only a few values at any zoom.
`LoudnessColumns` maps the columns of a window once for its width and the timeline then only draws
them; the playhead is a layer of its own, and the view model publishes the playing position apart
from the rest of the screen's state, so playback redraws the playhead and the clock and nothing
else.

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

## Speech recognition

After the events of a sleep are found, every event goes through speech recognition on the phone,
as the measurements on the test nights found best: no voice activity gate and no noise suppression
in front, because both lost whispers and removed almost no invented text. The interface is
`EventAnalysis`: a clip of an event goes in (the event with 0.5 s before and after it, cut back at
a recording's start and where the microphone was unavailable, like the lead-in), annotations with
their origin, producer and version come out. `SpeechRecognition` is the phone's analysis; an
analysis on a server would be another beside it.

`SpeechRecognition` hands the clip to the active model through `SherpaTranscriber` (sherpa-onnx's
offline recognizer, four threads at background priority, so the screen keeps the cores it needs),
in pieces of at most 30 s; clips under 0.3 s are not sent,
because sherpa-onnx fails on input without frames. `TranscriptFilter` then decides from the text:
tags are removed (also one whose bracket the model never closed, such as "(door"), short outputs
with phrases models write on silence and repetition loops are dropped, and output of fillers only
("mhm", "uh") makes a *murmur*. The result is one `TRANSCRIPT`
annotation over the event's stretch: the speech kind with the words, the speech kind without words
for a murmur, or the plain event kind, so that every event analysed counts as done. Whisper is
told the transcript language: it first detects the language itself and is asked again with the
user's language when it detected neither that nor English.

The filter has a version, stored as the producer version of the phone's transcripts. When it
changes, the app judges the stored transcripts of older versions again from their words at its
next start, so new rules reach nights recognised before without running the model again; a
transcript without words keeps its verdict, because the model's output is not stored. The
model-change hint compares only models, so a new filter version does not ask for a re-run.

Transcripts follow their events by stretch: the sleep screen shows an event with the kind and
text of the transcript over the same stretch, a server's before the phone's. Each model is a
producer of its own, so a result replaces only the same model's earlier one over the stretch, and an
event recognised by several models keeps a result of each; of these the active model's is shown,
else the latest. An event found again unchanged keeps its transcripts, and the phone's transcripts
of stretches that are no longer an event are removed with the new events.

"Recognise again with …" in an event's menu runs speech recognition on that event alone with an
installed model the user picks (with only one installed it leads to the settings, where more are
downloaded), to compare models without hours of re-running a night. It is a `TranscriptionRunner`
run of its own for one event, started by `Transcriptions` in the container's scope, so leaving
the screen does not stop it; the event shows that it is recognised meanwhile. Its open transcript
then lists every model's result with the model's name, the active model's first, and the
correction dialog lets the user take one into their text or delete one while another remains.
Once the user writes their own text, only that is shown.

The user corrects a speech event's text in a dialog, or says it is not speech, which a snackbar
undoes; every event action is in the ⋮ menu of its row. Any event can also be marked as speech:
that removes the user's own transcripts over it, and if speech recognition found no speech there
a transcript of the user's own makes it a murmur whose words they can then write. Either is a `TRANSCRIPT` annotation of the user over the event (the speech kind with the
user's words, none for a *murmur*, or the plain event kind) in place of the user's earlier ones
there. It wins over every analysis and, like a hidden mark, counts for every event it overlaps,
so it keeps to its event when a search with other settings finds it with other bounds, and no
run of speech recognition, with whichever model, touches it. `withTranscripts` combines the
events with their transcripts in plain Kotlin, so this rule is unit-tested.

A sleep's phone transcripts name the model that made them. When the active model is another than
the one whose result some events show, the sleep screen shows a hint with "Re-run with" the active model,
and so does an open transcript the user did not correct; both start the sleep's run.

`TranscriptionRunner` works through events one at a time and stores each result at once, so a run
that is stopped continues where it stopped; runs never overlap, so only one model is in memory
(about 1 GB for Parakeet). It loads the model only when an event waits, and closes it at the end.
When it runs is a setting:

- **After the stop** (default): `AppContainer` enqueues a WorkManager job after the events of a
  finished sleep are found and at process start. The job pauses while a recording runs and when
  the battery falls below 30 % without a charger; it then hands the rest to a job that waits for
  the charger.
- **Only while charging**: only the job that requires charging; WorkManager stops it when the
  charger is unplugged and runs it again on the next one.
- **Only manually**: no job of its own. The sleep's menu ("Run speech recognition", "... again"
  once events were recognised) starts a job for that sleep in every mode but off, which
  recognises all its events again.
- **Off**: no job at all, and switching it off cancels every job, also one started for a sleep.
  The sleep screen offers no run, not even of one event, and shows no status; transcripts made
  before stay.

A job runs as a foreground service with a progress notification when Android allows one (from
the background only for an app exempt from battery optimisation), else as normal work, which
Android may stop after ten minutes; the next job continues. The sleep screen shows a spinner while
its sleep is recognised and says on a tap what runs, what is done, or why events wait.

The user can pause the speech recognition of a sleep, in that pop-up, in the sleep's menu or with
the notification's Pause action, and continue it there. `TranscriptionPauses` keeps the paused
sleeps in the settings store, so a pause survives restarts; `TranscriptionRunner` leaves a paused
sleep out from its next event on and goes on with the next sleep that waits. A run over all
events of a sleep keeps the event it is at there too, because those events already have results:
once paused and continued, or stopped by Android and run again, it goes on there instead of at
the first event; a new run from the menu starts at the first. A run over waiting events needs no
position, as its events keep waiting. A pause holds
until the next run the settings start on their own, then every paused sleep goes on where it
stopped: after the next recording (after the stop), on the next charge (only while charging; a
pause made while charging waits for that charge to end, which a job that needs the charger
notices when WorkManager stops it), or only when the user continues or starts a run (manually).

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
the position in the sleep down to the sample. Speed changes keep the pitch. The sleep and a stretch played on its
own (`PlaybackSpeeds`) each have their own speed, which lasts as long as the player. The player belongs to
the sleep screen's `ViewModel` and pauses when the screen is no longer visible, because there is
no notification to control it.

An event is played on its own as a stretch from the *lead-in* before it to the tail after it, both
from the settings; the player pauses itself at the stretch's end. Lead-in and tail stop at the
start of a recording and at a stretch in which the microphone was unavailable
(`SleepTimeline.uninterrupted`), so neither the audio before a gap nor the silence of a taken
microphone is played around an event.

*Cut playback*, the default, plays the events the list shows in this way, one after the other.
The list's filter (`TagFilter`) is a choice of tags that combine with OR, made in the filter
window, where the user's tags are open and the system tags collapsed and set apart in the tertiary
colour; the chosen tags show as pills that wrap onto more lines, and a tap on one takes it out.
"All" before the filter button is selected while no tag is chosen and takes every chosen tag out.
With nothing chosen the list shows every event that is not hidden; hidden events show only while
the system tag hidden is chosen, so they can be gone through by ear and unhidden with the button
on their row. Hiding is undone from a snackbar. A line under the pills names how many events the
list shows and how long they last together, without lead-in and tail; the line under the app bar
names the same for all events of the sleep that are not hidden, whatever the filter, as the
library does.
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

A long press on an event row starts selection mode: while any event is selected, a tap selects
an event or takes it out, and an app bar of its own names how many are selected and how long they
last together, with buttons that share, tag or hide them all; Back ends it.
`EventSelection` keeps the selected ids, so a selection survives a change of filter, and an event
that a new search no longer finds leaves it. Event ids are unique across all sleeps, so the
library's tagged tab selects with it as well.

The buttons left and right of the play button step to the previous and the next listed event, in
full and in cut playback, to the start of its lead-in as cut playback plays it; playback plays on
or stays paused. They go from the last event whose stretch has begun at the playhead
(`neighbourEvents`): "previous" goes to the one before it while its stretch plays, and back to it
once the stretch is over. A button is disabled where no listed event lies in its direction.

### Sharing an event

"Share" in an event's menu opens a step that sends a stretch of the sleep's audio to another app
through Android's share sheet. `ShareStretch` starts it as cut playback plays the event, with the
settings' lead-in and tail, and lets a range slider move its ends up to 10 s around the event (at
least 0.5 s long), as far as the audio reaches without a gap or a taken microphone, the same edges
as the lead-in's. The step draws the loudness of that range with the shared stretch coloured, and
plays the stretch on the sleep's player to listen to it.

The step shares the stretch as a video by default, or as an audio file. WhatsApp, the main
target, drops a text sent along with an audio file but shows it as the caption of a video, so a
video carries a caption (`EXTRA_TEXT`) and an audio file none. The step's checkboxes choose what
the caption names: the event's name, the date and time, the transcript, the sleep and the
length, each where the event has it; the choice is a setting, so the next share starts with it.

`EventShares` reads the stretch's samples from the chunk files, fades them in and out like cut
playback, and encodes them with Android's own `MediaCodec` and `MediaMuxer`, so no library is
needed: the audio as AAC-LC at the recording's rate, alone in an M4A file or with a still picture
as H.264 video in an MP4 file, formats messengers and mail apps take everywhere. Each track is
encoded into memory first, because the muxer needs every track's format before it writes. The
picture is a `ShareCard` in the app's dark colours, drawn on an Android `Canvas`: WhatsApp shows a
single video preview up to 4:1 whole and crops a wider one, draws its play button in the middle and
the length at the bottom left, so the name (the event's kind for an event without one) stands top
left, and the date, time and transcript stand bottom right. A single event's card is 1200 × 300
pixels, its name ending before the middle. The video has one frame a
second, all the same picture, so it takes a few kB beyond the audio; its frames are converted to
YUV in plain Kotlin and handed to the encoder as images, which fits any encoder's memory layout,
and the encoder is one the phone names for that size.

Several selected events are shared together, but always as one file each, never joined: the step
offers one lead-in and one tail for all of them (`ShareStretches`, from none up to 10 s, starting
with the settings') instead of the single event's range slider, and each file gets the card of
its event. Android's `ACTION_SEND_MULTIPLE` carries one text for all files, so the caption has
one line per event, and every line, card and file name carries the event's number (`shareNumber`,
from 1; a single event has none), so lines and videos can be matched.

WhatsApp shows videos sent together as an album whose tiles crop each video to the tile's shape:
two are stacked as tiles of about 2:1, three as a 2:1 tile over two squares when the first video
is landscape, four or more as a grid of four squares whose fourth is covered by the count of the
rest, which are seen only once the album is opened; there the numbered caption is the only
mapping. `cardShapes` therefore gives two events cards of 1200 × 600, three a 1200 × 600 card and
two of 720 × 720, and four or more squares only. Shown that small, these cards set their text
larger, let the name and transcript wrap onto more lines, and show the number large in a disc top
right.

The step lives in the package `ui.share`, apart from the screens that open it. `EventSharing`
holds it and takes the events with their sleeps (`ShareItem`), so tagged events selected in the
library may come from several sleeps: every event carries its own sleep's audio (`EventAudio`),
its stretch, lead-in and tail stop at that sleep's edges and gaps, and its file is read from that
sleep's chunk files, read anew when the step opens. An event that its sleep's readable audio no
longer reaches (a chunk file went missing) is left out, and a share with none left reports that it
failed. `EventSharing.open` gives
one event the movable stretch and several one lead-in and tail. The sleep screen's
`SleepViewModel` holds one with the sleep's player, which listens to a single stretch; the
`LibraryViewModel` holds one without a player, for several tagged events, and its step offers no
listening.

Each file is named after its event's local date and time (`SleepTalk 2026-11-08 22-55-41.mp4`), of
several events after its number too (`SleepTalk 1 of 3 - 2026-11-08 22-55-41.mp4`), and
lies in the cache folder `shared/`, which holds only the latest share; a `FileProvider` hands the
files out, granting the receiving app read access to them alone. The audio encoder puts 1 024 samples
of priming silence before the audio and fills the last AAC frame, so a file plays up to about
0.2 s longer than its stretch, and the video lasts the stretch rounded up to whole seconds.

## Stats

The stats screen shows the numbers of one sleep, opened from the sleep screen, or of all nights,
opened from the library or switched to. `SleepRepository.storedSleeps` reads every finished sleep
whose events are found, with its recordings and events, again whenever an annotation changes (one
query each for all recordings, events and transcripts, however many sleeps there are), and
plain Kotlin computes the numbers from them: `CountedSleep` maps each event that is not hidden to
the instant it happened through the recordings' start instants and lengths (the database's sample
counts, without reading the audio files), `SleepStats` counts one sleep in ten-minute steps of the
local clock and compares its talk, the length of its speech events, with the other sleeps of its
kind, and `NightStats` averages over the nights. Naps are left out of all sleeps, because the
numbers are per night and a nap would pull every average down. The charts are drawn on a Compose
`Canvas`.

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

`SleepWindows` names a sleep (`SleepDate`: its kind and date) by the local time of its start: from
the time nights start until the time naps start is a night named after the evening it belongs to,
from the time naps start until nights start a nap of that day. Both times are a setting (by default
18:00 and 12:00); naps never start after nights, and when both start together every sleep is a
night. Because names follow the device's zone, a sleep is named as the user sees its times. The
name is stored with the sleep when it starts and never derived again.
