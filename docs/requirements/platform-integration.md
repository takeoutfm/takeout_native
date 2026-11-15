# Platform integration

How each app fits its platform beyond its own windows. Design:
[../design/platform-integration.md](../design/platform-integration.md). System media
controls are **PLAY-11** ([music-playback.md](music-playback.md)).

- **PLAT-1** Phone: **Android Auto** browses Recently Played, music, radio and podcasts and
  plays them.
- **PLAT-2** Phone: **voice**: "play X on TakeoutFM" (Assistant's `MEDIA_PLAY_FROM_SEARCH`)
  and the app's own actions (play an artist, song, album, radio, search) play the match.
- **PLAT-3** TV: voice search in the app.
- **PLAT-4** Watch: a **tile** with the player, and a **complication**.
- **PLAT-5** Mac: the player in the **menu bar** (a setting).
- **PLAT-6** Linux and tofm: **MPRIS**, each with its own bus name, so `playerctl -p tofm` and
  the desktop's media controls work; the KDE app uses the desktop's colour schemes and crash
  reporter.
- **PLAT-7** tofm: `tofm play [artist|radio|album|song] WORDS…` plays the match, handing it to
  a running tofm over D-Bus (`com.takeoutfm.tofm`); `tofm --status` prints what's playing.
- **PLAT-8** Phone: picture-in-picture for video and live ([video-playback.md](video-playback.md),
  [live-tv.md](live-tv.md)).

## Status

| Rule | Status |
|---|---|
| PLAT-1 Android Auto | ✅ (instrumented test of the tree) |
| PLAT-2 phone voice | ✅ |
| PLAT-3 TV voice search | ✅ |
| PLAT-4 tile, complication | ✅ |
| PLAT-5 menu bar | ✅ |
| PLAT-6 MPRIS | ✅ |
| PLAT-7 tofm play | ✅ |
| PLAT-8 picture-in-picture | ✅ |
