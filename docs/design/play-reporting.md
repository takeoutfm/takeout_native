# Play reporting — design

Requirements: [../requirements/play-reporting.md](../requirements/play-reporting.md).

## Shared (`:shared` `listen/`)

- `ListenRules.kt`: `countsAsListened` (LISTEN-1), `countsAsStreamListen` and
  `parseStreamTitle` (LISTEN-2).
- `ListenReporter`: sends a counted listen to Takeout (`reportPlays`) and/or ListenBrainz
  (`submitListens` and a connected account), "playing now" to ListenBrainz; failures go to the
  database's `pending_listen` table (at most 500) and `retry()` sends them.
- `ListenBrainzAccount`: the token (encrypted store) and the user it belongs to.
- `ListenSession`: the same rules for players that aren't Media3, fed events (track, playing,
  tick, stream title, stopped): the Mac/iOS `AudioPlayer` and the Linux `Player` (through the
  `listen…` calls, which `LinuxCalls.isOrdered` keeps in order).

## Per platform

| App | Decides when it counts | Settings |
|---|---|---|
| Phone, watch | `:android-media` `ListenTracker` (a Media3 listener in the music service) | `PlaySettings` (`reportPlays`, `submitListens`) |
| TV | the same | `TakeoutSettings` (Track Activity off by default); `data/listen/TokenHandoff`: a one-time page on the local network, shown as a QR code, to enter the ListenBrainz token from a phone |
| Mac/iOS | `ListenSession` via `SwiftCalls` | `PlaySettings` |
| Linux, tofm | `ListenSession` via the `listen…` calls from `Player` (C++) | `PlaySettings` |

Retries run at start and on returning to the foreground (`MainActivity.onStart` on Android,
`retryListens` elsewhere).
