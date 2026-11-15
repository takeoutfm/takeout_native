# Radio

Stations and live streams. Design: [../design/radio.md](../design/radio.md).

- **RADIO-1** Stations by type: **Genres, Decades, Other, Streams** (live internet radio).
- **RADIO-2** A station (not a stream) plays as a queue of tracks from the server, like any
  other (**PLAY-1**).
- **RADIO-3** A live stream has no length: no seeking, no skipping; the player shows it's
  live, and play/pause only.
- **RADIO-4** A stream's song (its ICY title, "Artist - Title") shows as title and artist.
- **RADIO-5** Songs heard on streams are kept on the device (**Stream History**) and listed
  with Recently Played; they count as listens for ListenBrainz (**LISTEN-2**).
- **RADIO-6** Live streams are never downloaded or cached (**DL-2**).
- ~~**RADIO-7** Custom stations from a search query~~: blocked on the server (no
  `POST /api/radio` route, no delete).

## Status

| Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|
| RADIO-1/2 stations | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RADIO-3/4 streams | ✅ | ⚠️ not checked | ⚠️ not checked | ✅ | ✅ | ✅ |
| RADIO-5 Stream History kept | ✅ | ✅ (shared service) | ✅ | ❌ | ✅ | ✅ (shared player) |
| RADIO-5 Stream History listed | ✅ | ❌ | ✅ | — | ❌ | ❌ |
