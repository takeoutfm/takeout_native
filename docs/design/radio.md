# Radio — design

Requirements: [../requirements/radio.md](../requirements/radio.md). The server's design is
`RADIO.md` in the Takeout server: live stations are schedules of library tracks
(`/api/radio/schedule`, `/api/radio/{id}/schedule`, with the server's `Time`); nothing is
streamed for them.

## Shared (`:shared` `music/`)

- `TakeoutMusicRepository`: stations by kind (`StationGroup`, `StationKind.Live`/`Stream`;
  `Station.live`), `radioNow` (what's on each live station, from one schedule request, and when
  to ask again), `stationQueue` (a live station's `liveQueue`, else the stream's playlist),
  `liveMore` (the schedule after the last queued track's end, so a queue behind live has no
  gap) and `replayQueue` (a Recently Played live station tunes in again).
- `LiveRadio.kt`: `RadioSchedule` and `onNow`; `LiveSlot` on a `QueueItem` (station, show,
  start / end by the server's clock, the clock skew when fetched), which is how every player
  knows a live track; and `LiveRadio`'s rules: `check` (as a track starts: as is, seek to the
  station, or jump to live), `keepBehind` (resume behind live or jump), `after`.
- A stream is a `QueueItem` with no ETag, which is how every player tells it apart (no
  seeking, never cached). `listen/ListenRules.parseStreamTitle` splits an ICY title;
  `music/StreamHistory` keeps the songs heard (database).

## Per platform

- Android (phone, TV, watch: `:android-media`): `LiveFollower` in `TakeoutMusicService`
  follows the schedule, so every entry point works; the slot rides in the media item's extras
  (`MediaItem.liveSlot`). Behind live is the session's extras (`BehindLiveKey`) and Jump to live
  a custom command, both through `MusicPlayer`. The system controls lose skip, seek and repeat
  for a live track. The phone's Radio tab (`RadioScreen`) fetches `radioNow` again when the
  first track on ends, while shown; Now Playing's `LiveRadioDetails`. The TV's Radio row shows each live station as the cover of what's on with its name and "Artist – Title" (`OnNowCard`), and its Now Playing has LIVE or Behind live / Jump to live, a progress bar that can't be focused, and only play, ♥ and the schedule; the watch lists what's on under each station and has LIVE or a Jump to live chip.
- Android: the music service reads ICY metadata and records Stream History (`TakeoutMusicService`).
- Linux, tofm: `Player` follows a live track through the core's `liveCheck`, `liveResume` and
  `liveMore` calls (the shared rules); `liveRadio`, `behindLive`, `jumpToLive()` for QML; it
  refuses skip and seek, and MPRIS says so. tofm's bar shows LIVE or behind it and the time left, `L` jumps to live, and its Radio page's live rows show what's on (`radioNow`, again as tracks end). `RadioPage.qml` refetches `radioNow` when a track
  ends, only while visible. `Player` also watches mpv's `metadata/by-key/icy-title` and
  reports `streamSongHeard`; the KDE app lists the songs under Recently Played
  (`RecentPage.qml`) from `Core.streamHistory`.
- Mac/iOS: `AudioPlayer` follows a live track through `SwiftCalls`' `liveCheck`, `liveResume`
  and `liveMore` (the shared rules): as each track starts it keeps to the schedule (seek or
  jump to live) and, when two or fewer are left (`LiveRadio.needsMore`), appends the next ones
  to the AVQueuePlayer; it refuses skip, seek and repeat and turns off the system's next and
  previous; `behindLive` and `jumpToLive()` for the views. Now Playing, the bars and the Mac's
  menu bar player show LIVE or Behind live / Jump to live, the time left and what's next; the
  queue is "Up next on <station>" with start times. `RadioView` shows what's on each live
  station (`LiveStationTile`), fetching `radioNow` again when the first track ends while
  shown; Recently Played replays through `replayQueue`. It also shows a stream's title and
  records Stream History.
