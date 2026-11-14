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
| Downloads, Playback Caching and the cache purge | [requirements/downloads-and-cache.md](requirements/downloads-and-cache.md) |
| Video audio and subtitle track choices | [requirements/video-tracks.md](requirements/video-tracks.md) |

Still in `PLAN.md` §4, to move here an area at a time: sign-in and session; library and
browsing; music playback and the queue; radio; podcasts; video playback; live TV; play
reporting (Track Activity, ListenBrainz); settings; platform integration (MPRIS, Android
Auto, tiles, voice).

## Design

| Area | File |
|---|---|
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
