# Play reporting: Track Activity and ListenBrainz

What the apps tell the Takeout server and ListenBrainz about what's played. Design:
[../design/play-reporting.md](../design/play-reporting.md).

## When a play counts

- **LISTEN-1** A track counts as **listened** 4 minutes in or half way through, whichever
  comes first; once per play of it.
- **LISTEN-2** A song on a live stream (radio) counts when the stream's title changes: heard
  from its start for at least 30 s, or for 4 minutes. The song playing when you tune in wasn't
  heard from its start. Titles not in "Artist - Title" form (station IDs, ads) never count.
- **LISTEN-3** Podcast episodes and video never count as listens (they keep progress instead).

## Where it goes

- **LISTEN-4** **Track Activity** (a setting) reports listened tracks to the Takeout server
  for its Activity and stats. On by default, as in the Flutter app; **off by default on the
  TV**.
- **LISTEN-5** **ListenBrainz** (a setting, once an account is connected): listened tracks and
  stream songs are submitted, and "playing now" is sent as a track starts. Takeout can't
  record stream songs (no ETag), so those go to ListenBrainz only.
- **LISTEN-6** Reports that fail (offline, server down) are kept on the device (at most 500)
  and sent again with the next, at start and when the app comes back to the foreground.
- **LISTEN-7** The ListenBrainz token is kept in the secure store; connecting checks it with
  ListenBrainz and says when it's refused or ListenBrainz can't be reached.
- **LISTEN-8** Where typing a token is hard, the app offers another way to enter it (the TV
  shows a QR code for a page on the same network).

## Status

| Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|
| LISTEN-1/3 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| LISTEN-2 stream songs | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| LISTEN-4 Track Activity | ✅ on | ✅ off by default | ✅ on | ✅ on | ✅ on | ✅ on |
| LISTEN-5 ListenBrainz | ✅ | ✅ | ⚠️ submits once connected, but no way to connect | ✅ | ✅ | ✅ |
| LISTEN-6 retry queue | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| LISTEN-8 token without typing | — | ✅ QR page | ❌ | — | — | — |

