# Sleep Talk Recorder

Recording a person's sleep and finding the moments in it where something happened.

## Language

### Sleeps

**Sleep**:
Everything recorded between going to bed and getting up, possibly from several recordings; either a night or a nap.
_Avoid_: Session, recording (for the whole sleep)

**Night**:
A sleep started between 18:00 and 11:59, named after the evening it belongs to ("night of 8 to 9 November"), even when it was started after midnight.
_Avoid_: Day

**Nap**:
A sleep started between 12:00 and 17:59, named after its own day ("nap on 9 November").
_Avoid_: Afternoon night

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

**Speech event**:
An event in which a later check heard likely speech; the mark is a guess layered on top of the event and never hides or removes it.
_Avoid_: Voice line, confirmed speech

**Transcript**:
Machine-written text of what was said in a speech event; may be incomplete or wrong.
_Avoid_: Subtitle, caption

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
