# Radio — design

Requirements: [../requirements/radio.md](../requirements/radio.md).

- `:shared` `music/TakeoutMusicRepository`: stations by type (`StationGroup`) and
  `stationQueue`. A live stream is a `QueueItem` with no ETag, which is how every player tells
  it apart (no seeking, never cached).
- `listen/ListenRules.parseStreamTitle` splits an ICY title; `music/StreamHistory` keeps the
  songs heard (database).
- Android: the music service reads ICY metadata and records Stream History (`TakeoutMusicService`).
- Linux, tofm: `Player` watches mpv's `metadata/by-key/icy-title` and reports `streamSongHeard`.
- Mac/iOS: `AudioPlayer` shows the stream title; it doesn't record Stream History.
