# Changelog

What changed for users in each release. The app shows this file under "What's new".
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions: [SemVer](https://semver.org/).

## [0.11.0] - 2026-11-12

### Added

- Sort events with tags. The tag button between an event's play button and its ⋮ menu, or in the bar of several selected events, opens the event's tags: Favourite and tags you make yourself, ticked, empty, or a dash when only some of the selected events have one. The window opens with your last changes ticked, so tagging the next event the same way takes the button and OK. The filter button above the events chooses any of your tags and the automatic ones (speech, plain event, murmur, named, hidden); the chosen ones show as pills that a tap removes, the list shows the events with any of them, and "All" shows every event again. Your favourites became the tag Favourite and keep their names, which no longer make an event a favourite. The library's "Tagged" tab lists every event with one of your tags or a name, with the same filter. "Keep tagged sleeps" under automatic deletion keeps such sleeps, as favourites were kept before.

## [0.10.1] - 2026-11-11

### Fixed

- An event whose transcript is only a sound tag with a missing closing bracket, such as “(door” or “(engine”, no longer counts as speech or shows under “Speech only”. Nights recognised before are corrected on their own after the update, without recognising them again.

## [0.10.0] - 2026-11-11

### Added

- A sleep shows how long its events last together, as in the library: next to its time and length on top for all its events (“630 events · 1 h”), and under the filter buttons for the events the chosen filter lists (“In the list: 42 events · 3 min 10 s”). Both follow every event you hide, unhide or keep as a favourite.

## [0.9.0] - 2026-11-11

### Changed

- Several events shared together are numbered: each line of the text sent along with videos starts with its number, and each video’s card and each file’s name carry the same number, so you can tell which line belongs to which video. The videos’ cards also take the shape of the tiles WhatsApp shows them in as an album, so the name, the time and the transcript are no longer cut off. A single shared event looks as before.

## [0.8.0] - 2026-11-11

### Added

- Hold a favourite in the library’s favourites tab to select it, then tap more favourites to add them, also from different sleeps. The bar on top shows how many you selected and how long they last together, and shares them or removes them from the favourites, which you can undo; Back ends the selection. Shared together, every favourite goes into a file of its own, each cut from its own sleep, and you stay in the library.

## [0.7.1] - 2026-11-11

### Fixed

- Sharing a favourite from the library opens the share step right there instead of in its sleep, so you stay in the library once it is shared or closed.

## [0.7.0] - 2026-11-11

### Added

- “Recognise again with …” in an event’s ⋮ menu recognises just that event again with another installed model, in seconds instead of a whole night. The open transcript then shows each model’s result with its name, the active model’s first. When correcting the text you can take over a model’s result or delete it; once you write your own text, only that is shown.
- Hold an event in a sleep’s list to select it, then tap more events to add them. The bar on top shows how many you selected and how long they last together, and shares them, keeps them as favourites or hides them all at once; Back ends the selection. Shared together, every event goes into a video or audio file of its own, with one lead-in and tail you choose for all of them, and the text sent along has a line for each.
- Speech recognition of a sleep can be paused and continued, to save battery: with “Pause” in its pop-up behind the status icon on the sleep screen, in the sleep’s ⋮ menu or in the notification while it runs. The pop-up then shows how many events are left. The pause holds until speech recognition would start on its own again (after your next recording, or on the next charge when it runs only while charging) or until you continue it, also after the app was closed; then it goes on where it stopped.

### Changed

- Sharing an event sends a short video by default: a card with the favourite’s name or else the kind of event, the date, the time and the transcript, with the event’s sound. WhatsApp and other messengers show the text sent along with a video, which they drop with an audio file. Checkboxes choose what that text names: the name, the date and time, the transcript, the sleep and the length; the app remembers your choice for the next share. “Audio” still sends the sound alone.

## [0.6.1] - 2026-11-11

### Fixed

- The app no longer turns black for good when you tap “Library” right after going back from another screen. A tap on the screen that is just closing no longer goes back a second time.

## [0.6.0] - 2026-11-11

### Added

- Buttons left and right of the play button jump to the previous and the next event in the list, also with a filter, and play on or stay paused. So you can go back to an event to mark it as a favourite once the next one has started.

## [0.5.1] - 2026-11-10

### Fixed

- When “Check now” in the settings finds a newer version, the settings offer its download and “Update” right there, with the same progress as home, instead of only on home.

## [0.5.0] - 2026-11-10

### Added

- “Share” in an event’s ⋮ menu, and a share button beside each favourite, send the event as an audio file to WhatsApp, Telegram, mail or any other app. Before sharing you can move its start and end and listen to it; it starts as cut playback plays the event, with its lead-in and tail.

## [0.4.2] - 2026-11-10

### Changed

- Cut playback starts 1 s before each event by default instead of 1.5 s. A lead-in you set yourself stays as it is.

## [0.4.1] - 2026-11-10

### Fixed

- “Export for analysis” shows its progress, says beforehand how much it will copy, and copies only what changed since the last export instead of everything again.

## [0.4.0] - 2026-11-10

### Added

- “Export for analysis” in the settings copies every sleep with its audio to the app’s folder on the phone’s storage, where a PC reads it over USB. “Delete” frees the space again.

## [0.3.1] - 2026-11-10

### Changed

- Tapping a favourite in the library opens its sleep at that event without playing it; its play button opens it and plays it.

## [0.3.0] - 2026-11-10

### Added

- Keep events as favourites and give them a name in their ⋮ menu. A star marks them, the “Favourites” filter lists only them, and the library’s new “Favourites” tab lists the favourites of all sleeps; tap one to hear it in its sleep. Automatic deletion keeps sleeps with favourites.
- Mark any event as speech in its ⋮ menu, for speech or a murmur that recognition missed; you can then write its words with “Correct text”. On an event you marked as not speech, the same item brings back what recognition found.

### Changed

- The actions of an event are only in its ⋮ menu; an open transcript no longer repeats them as buttons.
- “Full” and “Cut” no longer show icons, so their labels fit next to the speed button.

## [0.2.2] - 2026-11-10

### Fixed

- In the sensitivity sheet, “Apply” no longer breaks apart: when the buttons do not fit in one line, “Reset” and “Apply” move below “Listen”. The legend wraps by whole items, and the time labels below the chart no longer repeat the same time or overlap.

## [0.2.1] - 2026-11-10

### Fixed

- Labels that did not fit no longer break apart or get cut off: “Not speech” under a transcript, the choices for when speech recognition runs, the 12-hour time format, the shown stretch of the timeline and time ranges in the stats now fit, also at a larger font size.
- The stats chart of time talked per night is called “Time talked per night”, as it shows seconds as well as minutes.

## [0.2.0] - 2026-11-10

### Changed

- The timeline of a sleep moves like a map: pinch with two fingers to zoom around the point between them and move them to pan at the same time, swipe and let go to let it glide on, and double-tap to zoom in where you tap.

## [0.1.1] - 2026-11-10

### Fixed

- On phones with DuraSpeed, the setup now tells you to search the settings for “DuraSpeed”, because the app cannot open that page directly.

## [0.1.0] - 2026-11-09

The first beta: the app records your nights and lets you hear only what was said.

### Added

- Record a whole night or a nap in the background with the screen off, and stop from the notification or on its own at a latest stop time you choose.
- Every sleep is listed in the library as a night or a nap; you choose when nights and naps begin, and a new recording in the same night continues it.
- Play a sleep in full, or play only its events with a lead-in, a tail and soft fades. Every position shows the time into the recording and the clock time it happened.
- After the stop the app finds the events of the night and shows them on a timeline of its loudness that you can zoom and pan.
- Speech recognition on the phone writes down what was said and marks speech events; a murmur without words is marked too. Download the models in the settings, correct a transcript, mark an event as not speech or run the recognition again with another model.
- Make the search more or less sensitive for a whole sleep or around a single event, and see the events it finds while you move the slider.
- Hide events you do not want to hear and review the hidden ones.
- Stats of a sleep and across nights: how many events, how long you talked and when, compared with your other nights.
- Delete a sleep with a long press, or let the app delete sleeps after a number of days.
- A first-start setup asks for the language and walks you through the permissions a whole night needs, including the extra steps of some phone makers; home warns when one is missing.
- A recording cut off by a crash or an empty battery is repaired and kept; gaps and stretches in which another app had the microphone are marked.
- Choose the theme, the language (English or German), the time format and the lead-in and tail of the events.
- After an update the app shows once what is new; the settings list every update under "What's new".
- The app keeps itself up to date from its releases on GitHub: it looks for a new version on every start, downloads it on Wi-Fi (on mobile data after a tap) and installs it when you tap Update, or by itself once it is in the background and nothing records or plays. An update never interrupts a recording, and it is installed only when it is signed like the installed app.
- Everything stays on the phone: there is no account, and the internet is only used to download the speech recognition models and the app's updates.
