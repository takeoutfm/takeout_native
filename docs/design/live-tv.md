# Live TV — design

Requirements: [../requirements/live-tv.md](../requirements/live-tv.md).

## Shared (`:shared` `guide/`)

- `TakeoutGuideRepository`: `/api/guide` as `Guide` (channels, programs, break music).
- `LiveGuide`: the guide from now on, fetched again when it's 30 minutes old or less than an
  hour is left.
- `tune(channel, time)` → `Tune`: `Program` (from an offset), `Music` (a break track, `cut`
  when the next program cuts it off; `volume()` fades over `MusicFade`, 3 s) or `Slate`.
- `GuideLoader`: the guide page's guide, fetched in 6-hour windows as they scroll into view,
  merged; the schedule's horizon found up front for the day row.
- `LiveViews.kt`: the serialisable views (`LiveGuideView`, `LiveTune`, …) the Linux and Apple
  apps get through their calls (`guideReload`, `guideEnsure`, `tune`).

## Per platform

| App | Guide | Player |
|---|---|---|
| Phone | Home's Live type (`ui/live/LiveScreens`) | `ui/live/LivePlayerActivity` (landscape; Media3's controls through a player without seek or speed) |
| TV | the Live tab (`screens/live/`) | `LivePlayerScreen` |
| Mac/iOS | `LiveViews.swift` | `LivePlayer.swift` `LivePlayerModel` on VLC |
| Linux | `qml/LivePage.qml` | `qml/LivePlayer.qml` on `VideoView`: retunes at the slot's end (a coarse QML `Timer` can fire up to 5% early, so it waits out the rest); `VideoView` keeps mpv off the UI thread and skips cover art (`audio-display=no`) |
