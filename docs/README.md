# TakeoutFM native apps — documentation

What the apps must do, how they're built, and why. The apps: **phone** (`:android`), **TV**
(`:tv`), **watch** (`:wear`), **Mac/iOS** (`apple/`), **Linux** (`linux/`, the KDE app) and
**tofm** (`linux/tui/`, the terminal client), on the shared Kotlin core (`:shared`).

| Folder | Holds | Changes when |
|---|---|---|
| [`requirements/`](requirements/) | What every app must do, one file per feature area, platform-neutral, with numbered rules and a status table per app | behaviour is decided or changes |
| [`design/`](design/) | How it's built: where code and data live, how the pieces talk, per platform | the code's structure changes |
| [`decisions/`](decisions/) | One record per decision: the context, the choice, what it ruled out | a choice is made that someone could later question |

[`PLAN.md`](../PLAN.md) is the roadmap (phases, what's open) and links here.
[`CLAUDE.md`](../CLAUDE.md) is the guide for working in the repository (commands, devices,
rules learned the hard way).

## Requirements

| Area | File |
|---|---|
| Sign-in and session | [requirements/sign-in.md](requirements/sign-in.md) |
| Music playback and the queue | [requirements/music-playback.md](requirements/music-playback.md) |
| Radio | [requirements/radio.md](requirements/radio.md) |
| Podcasts | [requirements/podcasts.md](requirements/podcasts.md) |
| Play reporting: Track Activity and ListenBrainz | [requirements/play-reporting.md](requirements/play-reporting.md) |
| Downloads, Playback Caching and the cache purge | [requirements/downloads-and-cache.md](requirements/downloads-and-cache.md) |
| Video audio and subtitle track choices | [requirements/video-tracks.md](requirements/video-tracks.md) |

Still in `PLAN.md` §4, to move here an area at a time: library and browsing (navigation,
search, playlists, favorites, activity); video playback (the rest of it); live TV; settings;
platform integration (voice, Android Auto's tree, tiles).

## Design

| Area | File |
|---|---|
| Sign-in and session | [design/sign-in.md](design/sign-in.md) |
| Music playback | [design/music-playback.md](design/music-playback.md) |
| Radio | [design/radio.md](design/radio.md) |
| Podcasts | [design/podcasts.md](design/podcasts.md) |
| Play reporting | [design/play-reporting.md](design/play-reporting.md) |
| Downloads and the cache, per platform | [design/downloads-and-cache.md](design/downloads-and-cache.md) |
| Video track choices, per platform | [design/video-tracks.md](design/video-tracks.md) |

## Decisions

| # | Decision |
|---|---|
| [001](decisions/001-cache-budget.md) | The cache's size: Auto is 10% of the volume, at most 10 GB, with a free-space floor |
| [002](decisions/002-downloads-never-purged.md) | The viewer's own downloads are never purged; the app warns instead |
| [003](decisions/003-track-choices-by-language.md) | Track choices are remembered by language only, and shared by the video and live players |

## Conventions

- **Rule ids**: `AREA-n` (`CACHE-3`), never renumbered; a dropped rule is struck through and
  says why. Code, tests and commits may cite them.
- **Must / should**: *must* is required of every app the area applies to; *should* is wanted,
  and a gap is listed.
- **Status tables**, one per requirements file: ✅ done · ⚠️ partly (the note says how) ·
  ❌ not done · — doesn't apply · 🔨 done but not built or run yet.
- **Platform-neutral wording** in requirements ("the player", "the downloads index"); class and
  file names belong in design docs.
- A change in behaviour updates its requirements file **in the same commit**.
