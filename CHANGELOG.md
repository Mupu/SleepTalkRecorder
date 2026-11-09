# Changelog

What changed for users in each release. The app shows this file under "What's new".
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions: [SemVer](https://semver.org/).

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
