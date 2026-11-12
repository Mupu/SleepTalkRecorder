# Sleep Talk Recorder

Recording a person's sleep and finding the moments in it where something happened.

## Language

### Sleeps

**Sleep**:
Everything recorded between going to bed and getting up, possibly from several recordings; either a night or a nap.
_Avoid_: Session, recording (for the whole sleep)

**Night**:
A sleep started in the night window, from the time nights start until the time naps start (by default 18:00 to 11:59), named after the evening it belongs to ("night of 8 to 9 November"), even when it was started after midnight.
_Avoid_: Day

**Nap**:
A sleep started in the nap window, from the time naps start until the time nights start (by default 12:00 to 17:59), named after its own day ("nap on 9 November"). When both start at the same time there are no naps.
_Avoid_: Afternoon night

**Sleep window**:
The night window or the nap window: the stretches of the clock, set by the user, that decide whether a sleep is a night or a nap. A sleep is named once, when it starts, and keeps its name when the windows change.
_Avoid_: Time range, schedule

**Recording**:
One uninterrupted stretch of audio from start to stop; a sleep holds one or more recordings, and the time between them is a gap.
_Avoid_: Segment, take

### Moments in a sleep

**Event**:
A stretch of a sleep that is louder than the baseline, with a start and an end. Says nothing about what made the sound.
_Avoid_: Voice line, clip, utterance, detection

**Hidden event**:
An event the user marked as not interesting; it stays in the sleep but is skipped and greyed out like silence.
_Avoid_: Deleted event, false positive

**Tag**:
A label the user gives events to sort them: Favourite or one of their own, which they create and delete. A tag belongs to a stretch of the sleep, so it keeps to its event when the event is found again. A filter of tags lists the events with any of them; a sleep with a tagged or named event is kept from automatic deletion while the user keeps tagged sleeps.
_Avoid_: Label, category, folder

**Favourite**:
The tag the app provides: it works like the user's own tags but cannot be deleted. Events are kept as favourites to hear them again.
_Avoid_: Bookmark, starred event

**System tag**:
A tag the app sets on its own and the filter offers beside the user's: named, hidden, each event kind and murmur. It follows from the event and can be filtered by, but neither given nor taken away.
_Avoid_: Automatic label, smart tag

**Name**:
A short title the user gives an event, shown above its words and in what is shared; a named event has the system tag named.
_Avoid_: Title, caption

**Speech event**:
An event in which transcription found words or wordless murmuring; the mark is a guess layered on top of the event and never hides or removes it. The user can turn a speech event back into a plain event, and mark any event as speech.
_Avoid_: Voice line, confirmed speech

**Murmur**:
A speech event in which a voice is heard but no words are recognised.
_Avoid_: Mumble (in code), noise

**Transcript**:
Text of what was said in a speech event, written by a transcription model and correctable by the user; may be incomplete or wrong.
_Avoid_: Subtitle, caption

**Transcription model**:
The speech recognition model that writes transcripts on the phone; downloaded once, and one installed model is the active one.
_Avoid_: Engine, AI

**Baseline**:
The background loudness of a sleep at a given moment, tracked over time, against which events are found.
_Avoid_: Noise floor (in the UI), threshold

**Sensitivity**:
How far above the baseline a sound must be to become an event; set automatically per sleep and adjustable for that sleep or for a single event's surroundings.
_Avoid_: Threshold (in the UI), gain

### Playback

**Cut playback**:
Playing a sleep so that only its events are heard, each with a short lead-in and tail, and everything in between is skipped.
_Avoid_: Skip mode, highlights, condensed mode

**Full playback**:
Playing a sleep from start to end without skipping.

**Lead-in**:
The few seconds played before an event in cut playback, so the listener notices the jump.
_Avoid_: Pre-roll, padding (in the UI)
