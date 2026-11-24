# Downloads, Playback Caching and the cache purge

Media kept on the device so it plays offline: what the viewer downloads, and what Playback
Caching keeps as it plays, held within a size. Design: [../design/downloads-and-cache.md](../design/downloads-and-cache.md).
Decisions: [001](../decisions/001-cache-budget.md), [002](../decisions/002-downloads-never-purged.md).

## Terms

- **Download**: a whole media file (track, podcast episode, movie, TV episode) kept on the
  device, keyed by the file's ETag.
- **Explicit download**: one the viewer asked for (an album, playlist, podcast episode, title).
- **Cached download**: one Playback Caching added.
- **Partial track**: part of a track kept while streaming (only where the player can keep what
  it streams).
- **Volume**: the disk the downloads are on; its size and free space.

## Downloads

- **DL-1** Downloads are keyed by the media's ETag (stream URLs redirect to expiring signed
  URLs, so a URL is no key).
- **DL-2** Playback must read a download first, so it plays offline; live streams (no ETag) are
  never downloaded or cached.
- **DL-3** Each download records the album, playlist, podcast or title it came with; the
  Downloads page groups by it.
- **DL-4** Downloading something already downloaded does nothing, except **CACHE-4**.
- **DL-5** Video playback never writes to the cache; movies and episodes are only ever explicit
  downloads.

## Playback Caching

- **CACHE-1** Playback Caching is a setting, off by default. On, what plays is kept:
  - where the player can keep what it streams (Android): what streamed is kept as it plays, and
    a track kept whole is listed as a cached download;
  - elsewhere: a track or episode played to its end is downloaded (fetched once more) and
    listed as a cached download, with the queue it played in;
  - the watch: the queue is downloaded as it starts (cached).
- **CACHE-2** Off, nothing new is cached; what was cached still plays.
- **CACHE-3** Every download records whether it's cached or explicit. Downloads from before
  this was recorded count as explicit.
- **CACHE-4** Downloading something the viewer chose that was only cached makes it explicit.

## The cache size and purge

- **CACHE-5** Cached downloads (and partial tracks) are kept within a **budget**: the smaller of
  - the **size** set: *Auto* (default) is 10% of the volume, at most 10 GB; or 1, 2, 5 or
    10 GB;
  - what leaves the **free-space floor** free: 2 GB or 10% of the volume, whichever is
    smaller.
- **CACHE-6** Over the budget, cached entries are removed until they hold at most 90% of it,
  in this order: partial tracks; podcast episodes played to their end; then the least
  recently used (last played, or kept if never played since).
- **CACHE-7** Explicit downloads are **never** removed by the purge (decision 002).
- **CACHE-8** What the player has loaded (playing or paused, and the next item where the player
  reads ahead) is never removed.
- **CACHE-9** The purge runs at start, when a download finishes or is removed, when the size
  changes, and when the settings or Downloads page opens.
- **CACHE-10** Clear Cached removes every cached download and partial track (not what's
  loaded in the player, **CACHE-8**), and no explicit download.
- **CACHE-11** If the volume's size can't be read, nothing is purged.

## Warnings and big downloads

- **CACHE-12** When free space would be under the floor even with every cached entry gone
  (explicit downloads or other programs fill the disk), the settings and the Downloads page
  must warn, pointing at the Downloads page.
- **CACHE-13** Before downloading a file of known size (a movie), cached entries are removed,
  in the purge's order, if that's needed to leave the floor free.
- **CACHE-14** If even an empty cache leaves too little, the app asks before downloading
  ("needs 5.2 GB; clearing the cache frees only 3.1 GB"), offering *download anyway*; if it
  can't fit in the free space at all, it says so and doesn't offer it.
- **CACHE-15** A downloaded movie or episode played to its end is offered for removal ("Remove
  download?"), never removed by itself; not between episodes that play on. The Downloads page
  lists what's been watched, with one action to remove it.

## Settings and the Downloads page

- **CACHE-16** Settings: the Playback Caching switch; the cache size (Auto shows what it comes
  to on this device); what the cache holds; Clear Cached when it holds anything; the warning
  (**CACHE-12**).
- **CACHE-17** Downloads page: groups largest first; cached items marked *Cached*; the total
  split into downloaded and cached; the watched row (**CACHE-15**); the warning.
- **CACHE-18** Sizes show as "1.4 GB", "12 MB"; nothing cached shows no "used" figure (rather
  than a minimum like "1 KB").

## Status

| Rule | Phone | Watch | Mac/iOS | Linux | tofm | TV |
|---|---|---|---|---|---|---|
| DL-1–5 | ✅ | ✅ | ✅ | ✅ | ✅ | — streams only |
| CACHE-1 Playback Caching | ✅ as it streams | ✅ queue ahead | ✅ played to the end | ✅ played to the end | ✅ (shared player) | — |
| CACHE-3/4 cached vs explicit | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| CACHE-5–11 budget and purge | ✅ | 🔨 not run on a watch | ✅ | ✅ | ✅ | — |
| CACHE-6 partial tracks | ✅ | ✅ | — none | — none | — none | — |
| CACHE-12 warning | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| CACHE-13/14 room for a big download | ✅ | — no video | ✅ asks, or can't fit; clearing the cache first not yet seen | ✅ | ✅ | — |
| CACHE-15 watched removal | ✅ | — | ✅ Mac; iOS not yet seen | ✅ | ⚠️ Downloads page only (videos play in mpv) | — |
| CACHE-16 settings | ✅ | ✅ (tap steps through sizes) | ✅ | ✅ | ✅ (Enter steps through sizes) | — |
| CACHE-17 Downloads page | ✅ | ⚠️ marks and warning; no sizes or order | ✅ | ✅ | ✅ | — |

Known gaps: episodes have no size from the server, so **CACHE-13/14** only checks movies.
Mac/iOS was checked on the Mac (2026-11-23), the low-space rules with its debug
`-fakeFreeBytes`. VLC can stop *paused* at a file's end rather than *ended*; the players take
that as the end, or a watched download was never offered for removal.
