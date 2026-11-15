# TakeoutFM native apps — documentation

What the apps must do, how they're built, and why. The apps: **phone** (`:android`), **TV**
(`:tv`), **watch** (`:wear`), **Mac/iOS** (`apple/`), **Linux** (`linux/`, the KDE app) and
**tofm** (`linux/tui/`, the terminal client), on the shared Kotlin core (`:shared`).

| Folder | Holds | Changes when |
|---|---|---|
| [`requirements/`](requirements/) | What every app must do, one file per feature area, platform-neutral, with numbered rules and a status table per app | behaviour is decided or changes |
| [`design/`](design/) | How it's built: where code and data live, how the pieces talk, per platform | the code's structure changes |
| [`decisions/`](decisions/) | One record per decision: the context, the choice, what it ruled out | a choice is made that someone could later question |

[`PLAN.md`](../PLAN.md) is the roadmap: where each app stands and the gaps between apps.
[`CLAUDE.md`](../CLAUDE.md) is the guide for working in the repository (commands, devices,
rules learned the hard way).

## Requirements

| Area | File |
|---|---|
| Sign-in and session | [requirements/sign-in.md](requirements/sign-in.md) |
| Library and browsing | [requirements/library.md](requirements/library.md) |
| Music playback and the queue | [requirements/music-playback.md](requirements/music-playback.md) |
| Radio | [requirements/radio.md](requirements/radio.md) |
| Podcasts | [requirements/podcasts.md](requirements/podcasts.md) |
| Video playback | [requirements/video-playback.md](requirements/video-playback.md) |
| Video audio and subtitle track choices | [requirements/video-tracks.md](requirements/video-tracks.md) |
| Live TV | [requirements/live-tv.md](requirements/live-tv.md) |
| Downloads, Playback Caching and the cache purge | [requirements/downloads-and-cache.md](requirements/downloads-and-cache.md) |
| Play reporting: Track Activity and ListenBrainz | [requirements/play-reporting.md](requirements/play-reporting.md) |
| Settings | [requirements/settings.md](requirements/settings.md) |
| Platform integration | [requirements/platform-integration.md](requirements/platform-integration.md) |

The status tables were settled against the code on 2026-11-14; ❌ and ⚠️ cells are the open
gaps, collected in `PLAN.md`. The old feature checklists and phase log: `git show
4bce83c:PLAN.md`.

## Design

| Area | File |
|---|---|
| **Architecture**: modules, the shared core, each platform | [design/architecture.md](design/architecture.md) |
| The phone and TV apps: where things are | [design/android-apps.md](design/android-apps.md) |
| Sign-in and session | [design/sign-in.md](design/sign-in.md) |
| Library and browsing | [design/library.md](design/library.md) |
| Music playback | [design/music-playback.md](design/music-playback.md) |
| Radio | [design/radio.md](design/radio.md) |
| Podcasts | [design/podcasts.md](design/podcasts.md) |
| Video playback | [design/video-playback.md](design/video-playback.md) |
| Video track choices | [design/video-tracks.md](design/video-tracks.md) |
| Live TV | [design/live-tv.md](design/live-tv.md) |
| Downloads and the cache | [design/downloads-and-cache.md](design/downloads-and-cache.md) |
| Play reporting | [design/play-reporting.md](design/play-reporting.md) |
| Platform integration | [design/platform-integration.md](design/platform-integration.md) |

## Decisions

| # | Decision |
|---|---|
| [001](decisions/001-cache-budget.md) | The cache's size: Auto is 10% of the volume, at most 10 GB, with a free-space floor |
| [002](decisions/002-downloads-never-purged.md) | The viewer's own downloads are never purged; the app warns instead |
| [003](decisions/003-track-choices-by-language.md) | Track choices are remembered by language only, and shared by the video and live players |
| [004](decisions/004-monorepo.md) | One repository for every app |
| [005](decisions/005-sqldelight.md) | SQLDelight for the shared database |
| [006](decisions/006-linux-native.md) | Linux: Qt and Kirigami on the Kotlin/Native core, mpv for playback |
| [007](decisions/007-kmp-core.md) | A Kotlin Multiplatform core; UI and playback native per platform |
| [008](decisions/008-app-ids.md) | New app ids, installed beside the Flutter apps |
| [009](decisions/009-playback-cache.md) | Android caches playback in Media3's cache, keyed by ETag |
| [010](decisions/010-no-mobile-data-limits.md) | No mobile-data limits |
| [011](decisions/011-embedded-subtitles-only.md) | Embedded audio and subtitle tracks only |
| [012](decisions/012-tofm.md) | tofm: separate from the KDE app, video in mpv, covers as characters |
| [013](decisions/013-phone-media3-menus.md) | The phone keeps Media3's own player menus |
| [014](decisions/014-iphone-design.md) | iPhone: iPhone conventions on Android's structure |
| [015](decisions/015-apple-stream-proxy.md) | Apple video streams through a local proxy |
| [016](decisions/016-scope.md) | What the native apps include |

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
