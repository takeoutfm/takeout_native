# Live TV

The server's scheduled channels (`/api/guide`): what's on, the guide, and the live player.
Server design: the Go project's `CHANNELS.md`. Design: [../design/live-tv.md](../design/live-tv.md).

## Guide

- **LIVE-1** Live shows only when the library index says the server has a guide.
- **LIVE-2** On Now: each channel's program, its time, how long is left, and what's next.
- **LIVE-3** The Guide: channels by time, loaded in windows as it scrolls, a day row up to the
  end of the schedule, titles of programs that began off the left edge kept in view, a detail
  panel for the selected program (Watch live, Details).
- **LIVE-4** The guide keeps up with the time: it's fetched again when old or running short,
  and catches up at once when the computer wakes from sleep or the page is shown again (a guide
  left open overnight must show today, not yesterday). The guide is never answered from a kept
  copy: its server time would set the clock back to when it was kept (woken before the network
  was back, the guide ran 15 hours behind). A fetch that fails leaves the guide the app has, and
  counts as not fetched: it's tried again a minute later, not 30 minutes later.

## Tuning

- **LIVE-5** Tuning a channel plays what's on now, from where it is in its slot; between
  programs, **break music** plays under an up-next slate (fading out over the last 3 s when
  the next program cuts it off); a silent break or a program off air shows the slate alone.
- **LIVE-6** At the end of a slot the player tunes again, never earlier (a timer that fires
  early would reload what's ending).
- **LIVE-7** Break music plays even when the video is hidden: a song's cover art must not hold
  up its audio.
- **LIVE-8** Pause puts the viewer **behind live**: the program plays on from there until it
  ends; Jump to Live tunes again. Back from sleep or the background, a viewer at live is
  tuned to what's on now (what played on meanwhile is gone by); one behind live stays put.
- **LIVE-9** Channels change from the guide while playing; the channel, program and what's
  next show over the picture.
- **LIVE-10** Audio and subtitles follow the viewer's choices ([video-tracks.md](video-tracks.md)).
- **LIVE-11** Live never writes watch progress.
- **LIVE-12** Closing the player, or anything else, never freezes the app (Linux froze in a
  blocking call to mpv during break music).
- **LIVE-13** Picture-in-picture (phone) on leaving the app while a channel plays, following
  the schedule, with the slate in the small window during breaks.

## Status

| Rule | Phone | TV | Mac/iOS | Linux |
|---|---|---|---|---|
| LIVE-1–4 guide | ✅ | ✅ | ✅ | ✅ |
| LIVE-4 catches up after sleep | — | — | ⚠️ not checked | ✅ on wake (logind); a wake before the network ran behind (a kept copy), then sat on the old guide (a failed reload counted as fetched): both fixed, not yet seen on a real wake |
| LIVE-5 tuning, break music, slate | ✅ | ✅ | ✅ | ✅ |
| LIVE-6 never early | ✅ waits until the end | ✅ | ✅ | ✅ |
| LIVE-7 music under a hidden video | ✅ Media3 plays audio without a surface | ✅ | ✅ VLC the same | ✅ |
| LIVE-8 behind live, Jump to Live | ✅ | ✅ | ✅ | ✅ |
| LIVE-8 retunes after sleep or the background | ⚠️ not checked | ⚠️ not checked | ⚠️ on wake (Mac), back from the background (iOS); not yet seen on a real wake | ⚠️ not checked |
| LIVE-10 track choices | ✅ | ✅ | 🔨 subtitles | ✅ |
| LIVE-12 never freezes | ✅ | ✅ | ⚠️ VLCKit's stop is synchronous; no hang seen | ✅ |
| LIVE-13 picture-in-picture | ✅ | — | ❌ | — |

None on the watch or in tofm. LIVE-6 and LIVE-7 were found on Linux (2026-11-14); the others
hold by how they wait (until the slot's end) and by their engines.
