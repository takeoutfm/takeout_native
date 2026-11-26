# Radio

Live stations and internet radio streams. Design: [../design/radio.md](../design/radio.md);
the server's side is `RADIO.md` in the Takeout server (0.32 on).

- **RADIO-1** Stations by kind: **Live** (scheduled from the library) then **Streams**
  (internet radio), each in the server's order. Each live station shows what's on: the track
  ("Artist – Title", its cover) and the show while one is on, refreshed when that track ends.
- **RADIO-2** A stream plays as a queue of its sources from the server (**PLAY-1**). A live
  station that has nothing on plays its playlist, the schedule as plain tracks.
- **RADIO-3** A live stream has no length: no seeking, no skipping; the player shows it's
  live, and play/pause only.
- **RADIO-4** A stream's song (its ICY title, "Artist - Title") shows as title and artist,
  in the player and in the system's controls (notification, lock screen, Android Auto), with
  the station beside it.
- **RADIO-5** Songs heard on streams are kept on the device (**Stream History**) and listed
  with Recently Played; they count as listens for ListenBrainz (**LISTEN-2**).
- **RADIO-6** Live streams are never downloaded or cached (**DL-2**).
- ~~**RADIO-7** Custom stations from a search query~~: gone from the server with the old
  generated stations.
- **RADIO-8** Tuning in to a live station, from anywhere (the Radio page, search, voice,
  Android Auto, Recently Played), plays the track on now from where it is, by the server's
  clock, like real radio; then each track from its top.
- **RADIO-9** The player follows the schedule: the next tracks are fetched as each track starts
  and added to the queue; a track that starts more than 5 s off the schedule starts where the
  station is; an old queue (a Recently Played entry, repeat) tunes in again.
- **RADIO-10** A live station's track: play / pause only (no skip, seek or repeat, also in the
  system's controls), LIVE, a progress bar and the time left, and what's next. The queue is
  "Up next on <station>", not to pick from.
- **RADIO-11** The station, with the show while one is on ("Jazz FM · Jazz at Night"), is shown
  where the album would be, in the app and the system's controls.
- **RADIO-12** Pause and resume: within 5 minutes (or the paused track's remaining time, if
  longer) playback carries on behind live, showing "Behind live · Jump to live"; longer jumps
  to live. Jump to live tunes in again at any time.
- **RADIO-13** Scheduled tracks are library tracks: listens as for any track (**LISTEN-1**),
  ♥ works.

## Status

| Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|
| RADIO-1 live and streams | ✅ | ✅ (not checked on the TV) | ✅ | ✅ | ✅ | ✅ |
| RADIO-2 streams | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RADIO-3/4 streams | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RADIO-5 Stream History kept | ✅ | ✅ (shared service; recorded since 2026-11-24) | ✅ | ✅ | ✅ | ✅ (shared player) |
| RADIO-5 Stream History listed | ✅ | ✅ on music Home | ✅ | ✅ under Recently Played | ✅ under Recently Played | ✅ under Recently Played |
| RADIO-8/9 tune in, follow | ✅ | ✅ (shared service; not checked) | ✅ | ✅ (checked on the iPhone) | ✅ | ✅ (shared player) |
| RADIO-10/11 live controls | ✅ | ✅ (not checked on the TV) | ⚠️ no time left or station name (a small screen) | ✅ (checked on the iPhone) | ✅ | ✅ |
| RADIO-12 behind live | ✅ | ✅ (not checked on the TV) | ✅ | ✅ (checked on the iPhone) | ✅ | ✅ (L jumps to live) |
| RADIO-13 listens, ♥ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
