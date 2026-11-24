# Podcasts

Series, episodes, subscriptions and episode progress. Design: [../design/podcasts.md](../design/podcasts.md).

- **POD-1** Series: all, or **subscribed**; subscribe and unsubscribe (on the server, so every
  device follows).
- **POD-2** A series lists its episodes, newest first, each with its progress ("N min left",
  or played).
- **POD-3** Episodes play in the music player, as a queue of the series' episodes.
- **POD-4** Where an episode got to is saved (keyed by its GUID, synced with the server like
  video progress): while playing, on pause and on moving to another item; one played to its
  end is complete. Playing it again resumes there.
- **POD-5** Episodes are never listens (**LISTEN-3**).
- **POD-6** Episodes can be downloaded and removed (**DL**, [downloads-and-cache.md](downloads-and-cache.md));
  finished ones are the first cached downloads to go (**CACHE-6**).
- **POD-7** An episode can be **marked played** or not played without playing it.

## Status

| Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|
| POD-1 subscribed, subscribe | ✅ | ✅ | ⚠️ lists the subscribed (all if none); can't subscribe | ✅ | ✅ | ✅ |
| POD-2/3 episodes, playing | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| POD-4 progress | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| POD-6 downloads | ✅ | — streams only | ✅ | ✅ | ✅ | ✅ |
| POD-7 mark played | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ (m on an episode) |
