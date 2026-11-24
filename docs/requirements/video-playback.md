# Video playback

Movies and TV episodes: resuming, progress, episodes in a row, the player's controls. Track
choices: [video-tracks.md](video-tracks.md); live channels: [live-tv.md](live-tv.md). Design:
[../design/video-playback.md](../design/video-playback.md).

## Progress

- **VIDEO-1** Progress is saved by the media's ETag, synced with the server, every 10 s while
  playing, on pause and on leaving; kept on the device until the server has it (offline too).
- **VIDEO-2** Only once playback really started: a title that fails to load never resets its
  saved place.
- **VIDEO-3** A title resumes where it was left unless it's in its first 10 s or **finished**:
  within 2 minutes of the end, or (films and episodes) in the last 8%, which covers the
  credits. Start Over plays from the beginning.
- **VIDEO-4** Continue Watching lists what's started and unfinished, most recent first.

## Playing

- **VIDEO-5** A downloaded title plays from the device (offline too); others stream.
- **VIDEO-6** An episode that ends goes on to the next one, from its start; anything else
  ends by leaving the player (back to where it was played from), not a stopped picture.
- **VIDEO-7** Previous and next episode, skip back and ahead, play/pause, seek.
- **VIDEO-8** Playback speed (0.5× to 2×).
- **VIDEO-9** Full screen (desktop) and picture-in-picture where the platform has it (phone:
  on leaving the app while playing, with play/pause in the small window).
- **VIDEO-10** Music pauses when a video starts.
- **VIDEO-11** The player never blocks the interface waiting on the media engine (a frozen
  window was the result on Linux).

## Status

| Rule | Phone | TV | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|
| VIDEO-1–4 progress, resume | ✅ | ✅ | ✅ | ✅ | ✅ (mpv's IPC) |
| VIDEO-5 downloads | ✅ | — streams only | ✅ | ✅ | ✅ |
| VIDEO-6 next episode, leave at end | ✅ | ✅ | ✅ (leaving seen on the Mac) | ✅ | ⚠️ mpv's own |
| VIDEO-7 previous / next episode buttons | ❌ next plays by itself | ✅ | ✅ | ✅ | — mpv's |
| VIDEO-8 speed | ✅ Media3's menu | ✅ | ❌ | ✅ | — mpv's |
| VIDEO-9 full screen / PiP | ✅ PiP | — | ✅ full screen | ✅ full screen | — mpv's |
| VIDEO-10 music pauses | ✅ | ✅ | ✅ | ✅ | ✅ |
| VIDEO-11 never blocks | ✅ | ✅ | ⚠️ VLCKit's stop is synchronous; no hang seen | ✅ | — |

The watch has no video.
