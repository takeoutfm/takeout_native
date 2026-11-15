# Music playback and the queue

Playing albums, mixes, stations, playlists and tracks; the queue; Recently Played; the player's
controls and the system's. Design: [../design/music-playback.md](../design/music-playback.md).
Radio streams and podcasts: [radio.md](radio.md), [podcasts.md](podcasts.md).

## Playing

- **PLAY-1** What plays is a **queue** from the server: an album, an artist's mix (Shuffle,
  Popular, Radio), a station, a playlist, favorites, search results, or a single track (its
  track playlist, which goes on with similar tracks).
- **PLAY-2** Play from a chosen track, or **shuffled**.
- **PLAY-3** A track that's downloaded plays from the device (offline too); others stream.
- **PLAY-4** **Play next** puts one track after the current one; **add to queue** puts it at
  the end.
- **PLAY-5** **Repeat**: off, all, one. Repeat All stays for every queue until turned off;
  Repeat One is for the track playing, and a new queue goes back to the saved setting.
- **PLAY-6** Music pauses when a video or live channel starts.

## Recently Played

- **PLAY-7** The last 50 queues played on this device, newest first; replaying one moves it to
  the front, and resumes where it was left (track and position).
- **PLAY-8** Kept on the device only; cleared on sign-out (its URLs belong to that server);
  the viewer can clear it.

## The player

- **PLAY-9** Now Playing: cover, title, artist, album, a seek bar, previous · back 10 s ·
  play/pause · ahead 30 s · next, repeat, the queue, favorite.
- **PLAY-10** A small player (or bar) shows what's playing wherever the viewer is, and opens
  Now Playing.
- **PLAY-11** The system's controls work: media keys and headsets, the lock screen and
  notification (phone), Now Playing and remote commands (Mac/iOS), MPRIS (Linux, tofm),
  Android Auto (phone), the watch's tile and complication.
- **PLAY-12** A track that can't play says why; playback goes on with the next.

## Status

| Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|
| PLAY-1/2 queues, shuffle | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| PLAY-3 downloads first | ✅ | — streams only | ✅ | ✅ | ✅ | ✅ |
| PLAY-4 play next, add to queue | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| PLAY-5 repeat | ✅ | ✅ | ⚠️ the saved setting applies; no control on the watch | ✅ | ✅ | ✅ |
| PLAY-7/8 Recently Played | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| PLAY-6 music pauses for video | ✅ audio focus | ✅ audio focus | — | ✅ | ✅ | ✅ |
| PLAY-9 Now Playing controls | ✅ | ✅ | ⚠️ previous, play/pause, next | ✅ | ⚠️ no back 10 s / ahead 30 s | ✅ (keys `[` `]`) |
| PLAY-10 small player | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (the bar) |
| PLAY-11 system controls | ✅ media session, Android Auto | ✅ media session | ✅ tile, complication | ✅ | ✅ MPRIS | ✅ MPRIS (`tofm`) |
| PLAY-12 says why a track can't play | ✅ | ✅ | ❌ | ❌ logged only | ❌ logged only | ❌ |
