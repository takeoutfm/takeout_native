# TakeoutFM native apps — plan

The roadmap: where each app stands, what's left, and the gaps between apps. What the apps must
do, how they're built and why are in [`docs/`](docs/README.md): requirements (numbered rules
with a status table per app), design (starting with [the architecture](docs/design/architecture.md))
and decision records.

The phase-by-phase log of how each app was built (2026-10-25 to 2026-11-14) is in git:
`git show 4bce83c:PLAN.md`.

## Goals

1. **Native apps for every platform** the Flutter apps served and more: phone and tablet, TV,
   watch, Mac, iPhone and iPad, Linux desktop and terminal.
2. **One core for the Takeout domain** (API, models, queues, progress, listens, offline):
   Kotlin Multiplatform, so a fix lands in every app ([007](docs/decisions/007-kmp-core.md)).
3. **Native UI and playback** on each platform, with its system integration.
4. **The apps behave the same**: the requirements apply to every app, and their status
   tables show where one falls short.
5. **No server changes needed**, except where a feature is waiting on the server (custom
   stations).

## Where each app stands

| App | State | Open |
|---|---|---|
| Phone (`:android`) | Released (F-Droid repository, with the TV and watch: one version) | the gaps below |
| TV (`:tv`) | Released | the gaps below; `docs/design/tv.md` lists its own open items |
| Watch (`:wear`) | Released | not yet checked: downloads and offline play, the complication; downloads aren't limited to Wi-Fi |
| Mac/iOS (`apple/`) | In progress | build and check the cache purge and track choices (written on Linux, not yet compiled); iPad check; downloads, radio and podcasts on the iPhone; a real device (needs a team); lock screen and Control Center, picture-in-picture, Siri; CarPlay not started |
| Linux, KDE (`linux/`) | Working, packaged as debs | Flatpak (KDE runtime 6.x, an mpv module) |
| tofm (`linux/tui/`) | Working, packaged as a deb | the gaps below |

## Gaps between apps

From the requirements' status tables (❌ and ⚠️ there), roughly in order of how much a viewer
would notice. Fixing one updates its status table in the same commit.

| Gap | Apps missing it | Rule |
|---|---|---|
| Say why a track can't play | Linux, tofm, watch | PLAY-12 |
| Ask before signing out | phone, TV, watch | SIGN-10 |
| Play next / add to queue | TV, watch | PLAY-4 |
| Previous / next episode buttons in the video player | phone | VIDEO-7 |
| Back 10 s / ahead 30 s in Now Playing | Linux (watch: only previous, play, next) | PLAY-9 |
| Playlists: create, add | TV, watch, tofm | LIB-13 |
| Playlists: remove a track, delete | TV, watch, tofm; Linux can delete | LIB-13 |
| Search | watch; the TV searches video only | LIB-12 |
| Favorite an artist; the favorite artists list | artist: watch, Linux, tofm; list: phone, watch, tofm | LIB-6, LIB-8 |
| Activity (play stats) | TV, watch, Linux, tofm | LIB-14 |
| Recommendation lists | Linux, tofm | LIB-3 |
| Mark an episode played | phone, TV, watch, tofm | POD-7 |
| Subscribe to a podcast; repeat | watch | POD-1, PLAY-5 |
| Connect ListenBrainz | watch | LISTEN-5 |
| Link another device | Linux, tofm | SIGN-11 |
| Stream History listed with Recently Played | Linux (keeps it, never lists it: see radio.md), TV, tofm | RADIO-5 |
| Subtitle languages compared as written ("eng" ≠ "en") | Linux | TRACKS-6 |
| Watched videos offered at the end | tofm (Downloads page only) | CACHE-15 |
| Sizes and order on the Downloads page | watch | CACHE-17 |
| Picture-in-picture for video and live TV | iOS (waits for VLCKit 4, still alpha; the Mac floats on top instead) | VIDEO-9, LIVE-13 |

Waiting on the server: custom radio stations (RADIO-7; no `POST /api/radio`, no delete). Not
done anywhere: the artist page's Want List and Tracks Like This.

## Testing

- `:shared`: `commonTest` on the JVM (`testDebugUnitTest`) and natively (`linuxX64Test`;
  `macosArm64Test` on a Mac). HTTP with Ktor's `MockEngine`, the database in memory.
- `:android-media`, `:tv`: JVM unit tests. The phone: instrumented tests run with
  `am instrument` (`BrowseTreeTest`, `TrackPicksTest`, `SavedRepeatTest`), never
  `connectedAndroidTest` (it uninstalls the app and its sign-in).
- Linux: `ctest` (the C API smoke test, `http_host_test`, `tofm_test`: tofm's screens rendered
  as text).
- By hand: the phone emulator (`Pixel_9`), the TV emulator and Chromecast (release builds),
  the watch emulator (`Wear_Round_API_36`), the iPhone simulator, this Linux desktop. Playing
  changes the user's real stats and progress: keep test playback short. See `CLAUDE.md`.
