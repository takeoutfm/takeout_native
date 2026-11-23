# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Native clients for the Takeout media server (a separate Go project): phone, TV, watch,
Mac/iOS, Linux (KDE) and a terminal client, on one Kotlin Multiplatform core.

- **`docs/`** (`docs/README.md`): what the apps must do (`requirements/`: numbered rules with a
  status table per app), how they're built (`design/`, starting with `architecture.md`; the
  phone and TV in `android-apps.md`) and why (`decisions/`). **A change in behaviour updates
  its requirements file, status table included, in the same commit.** Read the area's
  requirements and design before changing it.
- **`PLAN.md`**: the roadmap: where each app stands and the gaps between apps.
- Per platform: `docs/design/tv.md` (the TV's screens; read before larger TV changes),
  `apple/README.md` (building the Apple apps), `fdroid/README.md` (publishing).

Licensed GPL-3.0-or-later (`COPYING`; notices in `LICENSES.md`); the Takeout server is
AGPL-3.0. Files from Google's JetStream sample (mostly `tv/`) keep their Apache-2.0 headers.

| Module | What |
|---|---|
| `:shared` | The Kotlin Multiplatform core (Android, Apple, Linux). No Android or `java.*` imports in `commonMain`; kotlinx-datetime, not `java.time`; no Hilt. Tests in `commonTest`. |
| `:android-media` | Android playback, downloads and the cache for the Android apps (`com.takeoutfm.media`). Plain constructors, no Hilt. |
| `:android` | Phone and tablet (`com.takeoutfm.android`, debug `.debug`). |
| `:tv`, `:tv-benchmark` | Android TV (`com.takeoutfm.tv`), from JetStream; its macrobenchmarks. |
| `:wear` | Wear OS (`com.takeoutfm.wear`, debug `.debug`). |
| `apple/` | macOS and iOS (`com.takeoutfm.mac`, `com.takeoutfm.ios`); Kotlin for it in `shared/src/appleMain`. |
| `linux/` | The KDE app (`com.takeoutfm.linux`) and `tofm` (`tui/`), on `libtakeout.so` (C API `linux/src/takeout.h`; Kotlin in `shared/src/linuxMain`). |

## Commands

JDK 17 (`export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`: the default java is newer and
Gradle fails with a bare version number); `local.properties` must point `sdk.dir` at the
Android SDK (it isn't committed).

```
./gradlew :android:assembleDebug            # phone app debug APK (com.takeoutfm.android.debug)
./gradlew :android:assembleRelease          # signed with android/key.properties if present, else debug
./gradlew :wear:assembleDebug               # watch app debug APK (com.takeoutfm.wear.debug)
./gradlew :tv:assembleDebug                 # debug APK
./gradlew :tv:assembleRelease               # minified release APK (android/key.properties, else debug key)
./gradlew :tv:testDebugUnitTest             # TV JVM unit tests (JUnit 4)
./gradlew :tv:testDebugUnitTest --tests '*FormatDollarsTest'   # one test class
./gradlew :android-media:testDebugUnitTest  # playback unit tests
./gradlew :shared:testDebugUnitTest         # shared commonTest, run on the JVM
./gradlew :shared:compileKotlinMacosArm64   # shared macOS klib (appleMain), compiles on Linux
./gradlew :shared:macosArm64Test            # shared commonTest on Kotlin/Native (Mac only)
./gradlew :shared:linuxX64Test              # shared commonTest on Kotlin/Native, here
./gradlew :shared:linkReleaseSharedLinuxX64 # libtakeout.so for the Linux app
cmake -S linux -B linux/build -G Ninja && cmake --build linux/build   # Linux apps (builds libtakeout.so via Gradle)
ctest --test-dir linux/build                # C API smoke test, http_host_test, tofm_test (tofm's screens as text)
(cd linux/build && cpack)                   # takeoutfm-core_, takeoutfm_, tofm_<version>_amd64.deb; copy to /tmp, apt install all three
./gradlew :tv:compileDebugKotlin            # fast compile check
```

- Releases: one version for the phone, TV and watch apps (`takeout.versionCode` /
  `takeout.versionName` in `gradle.properties`), all signed with `android/key.properties`.
  The Mac/iOS apps carry the same version (`apple/project.yml` `MARKETING_VERSION` /
  `CURRENT_PROJECT_VERSION`); `fdroid/publish.sh` stops if they differ.
  `fdroid/publish.sh [--deploy]` publishes them to the F-Droid repo in `~/fdroid`.
- Phone instrumented tests (Android Auto's tree, `TrackPicksTest`, …): `./gradlew
  :android:assembleDebug :android:assembleDebugAndroidTest`, `adb install -r` both APKs (`-t`
  for the test one), then `adb shell am instrument -w
  com.takeoutfm.android.debug.test/androidx.test.runner.AndroidJUnitRunner` (`-e class …` for
  one) — never `connectedAndroidTest`, which uninstalls the app and its sign-in.
- Formatting: Spotless/ktlint is configured via `buildscripts/init.gradle.kts` but hasn't been
  used in this port; it would stamp Google's license header onto Takeout files.

## Devices and running the apps

- `installDebug` installs on **every** connected device. To target one, build and use
  `adb -s <serial> install -r <apk>` (serials from `adb devices -l`).
- **Phone**: the `Pixel_9` AVD (emulator-5556 when the TV emulator is also up); launch with
  `adb shell monkey -p com.takeoutfm.android.debug -c android.intent.category.LAUNCHER 1`. Sign
  in with the device code (the user approves it at <server>/link); never type the user's
  password. Offline: `adb shell svc wifi disable; adb shell svc data disable`. It has little
  free space: test video downloads with a small title (The Chronicles of Riddick: Dark Fury,
  284 MB). Voice: `adb shell "am start -a com.takeoutfm.action.PLAY_ARTIST -e artist 'Beck' -n
  com.takeoutfm.android.debug/com.takeoutfm.android.MainActivity"`.
- **TV**: the real one is a Chromecast with Google TV over wireless debugging: always install
  the **release** APK (`tv/build/outputs/apk/release/tv-release.apk`); debug builds are far
  slower and hide or cause problems that don't match real use. Its adb serial changes whenever
  wireless debugging is toggled. Launch: `adb shell monkey -p com.takeoutfm.tv -c
  android.intent.category.LEANBACK_LAUNCHER 1`.
- **Watch**: the `Wear_Round_API_36` AVD; add the tile with `adb shell am broadcast -a
  com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component
  com.takeoutfm.wear.debug/com.takeoutfm.wear.tile.PlayerTileService`.
- **Apple**: the `iPhone 17 Pro` simulator; Live debug flags `-livePage YES`, `-playLive
  "Action"`.
- **Linux**: run `linux/build/takeoutfm` (single instance: if the installed one is running,
  a second just raises it); sign in with the device code. Pause through MPRIS to look at the
  window without playing (`qdbus6 org.mpris.MediaPlayer2.takeoutfm /org/mpris/MediaPlayer2
  org.mpris.MediaPlayer2.Player.Pause`). Offline: `unshare -rn linux/build/takeoutfm`. Its data:
  `~/.local/share/takeoutfm` (downloads in `media/`). Screenshots: `spectacle -b -n -a -o
  file.png`. `tofm`: run `linux/build/tofm` in a real terminal (the app's Terminal panel can't
  answer terminal queries); data in `~/.local/share/tofm`, log `~/.local/state/tofm/tofm.log`.
  Packages needed: libmpv-dev, libkf6{i18n,coreaddons,dbusaddons,crash,colorscheme,config}-dev
  (Ubuntu's colorscheme-dev doesn't pull in config-dev), libftxui-dev (5.x), libchafa-dev.
- **Playing changes the user's real data**: music adds to their Takeout stats (Track Activity
  is on by default) and ListenBrainz; a video or episode played ≥10 s changes their watch
  progress; subscribing, favoriting and playlists change their account. Keep test playback
  short, and don't test with their real ListenBrainz account.
- The emulators can't decode 10-bit HEVC (e.g. Superman 2025, The Phoenician Scheme) or AC-3.
  H.264 test titles: Breaking Bad episodes (several audio and subtitle tracks), Godzilla (1954).

## Conventions

- Linux QML strings: `KI18n.i18n()` with `import org.kde.ki18n`, never `qsTr`. Pages derive
  from `AppPage` (the Now Playing bar is its footer) and `import TakeoutFM` for `Core` /
  `Player` / `VideoView`. `src/Core` is the QML singleton (its constructor needs a parent, else
  QML makes a second core). Two QtCore `Settings` objects only read the file when created: share
  one.
- TV: keep server access in the `data/` layer; JetStream's screens talk to `MovieRepository`,
  mapped by `TakeoutMovieRepository` (see `docs/design/android-apps.md`).

## Rules learned the hard way

- Never sort/map the whole library or decode large JSON on the main thread — the Movies tab was
  killed for ANR on the Chromecast (it was fine on the emulator). Use `listFlow` (runs on
  `Dispatchers.Default`) / `withContext`, and compute sort keys once (`sortedByTitle`).
- Focus: request initial focus only once an item is laid out (`focusOnInitialVisibility`), never
  straight after the list exists — that crashes lazy grids.
- Subtitles and audio are **embedded tracks only**; don't add server-side subtitles.
- Track choices are the same in every app: `docs/requirements/video-tracks.md` (only the
  viewer's picks are saved, never the app's own; by language; shared by the video and live
  players).
- Linux `VideoView`: every mpv call from the UI thread must be async — a blocking one can wait
  on the video output, which waits on the UI thread to draw, and the window freezes.
- Avoid timers where an event exists (the user prefers it): `Core.woke` (logind) for sleep,
  visibility and activation for a page coming back. A timer that must stay should run only
  while needed and `repeat` (a one-shot that re-arms itself stops for good if one tick goes
  wrong: the Live guide stayed on yesterday). QML `Timer`s are coarse (up to 5% early): never
  act on "the time has come" without checking the clock.
- State that several pages show or edit (playlists, favorites, subscriptions, progress) is a `StateFlow` in the shared
  repository that its edits update and pages observe, not a reload counter or "changed" signal
  in an app: a Mac page under a pushed one never reappears to reload (decision 017).
- Swift's synthesized `Codable` fails on a missing key: a field added to a saved JSON type
  must be optional, or every existing file stops decoding.
- C++ range-for over a member of a temporary (`for (x : f()->rows)`) uses freed memory; keep
  the temporary in a variable first.

## Testing on devices

- On-device checks use `adb shell input …` plus `screencap`. Take a screenshot before every
  tap on a screen you haven't just seen: the same spot does different things on different
  pages (a tap meant for a menu once favorited a movie), and the TV's Profile screen has a
  **Sign out** card.

## Working with the user

- Don't commit until asked; commit messages end with the `Co-Authored-By` trailer. There is no
  git remote.
- After UI changes, verify on the emulator (and the TV when connected) before reporting done.
