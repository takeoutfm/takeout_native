# Architecture

How the apps are put together: one Gradle build, a Kotlin Multiplatform core shared by every
app, and each platform's own UI and playback on top. The per-area design docs build on this.

## Repository

One repository (decision [004](../decisions/004-monorepo.md)), one Gradle build for the
Kotlin parts, with the Apple and Linux apps built by their own tools against the same core.

| Path | What | Built by |
|---|---|---|
| `shared/` | `:shared`, the Kotlin Multiplatform core | Gradle |
| `android-media/` | `:android-media`, Android playback for the Android apps | Gradle |
| `android/` | `:android`, the phone and tablet app (`com.takeoutfm.android`) | Gradle |
| `tv/`, `tv-benchmark/` | `:tv`, the Android TV app (`com.takeoutfm.tv`), from Google's JetStream sample; its macrobenchmarks | Gradle |
| `wear/` | `:wear`, the Wear OS app (`com.takeoutfm.wear`) | Gradle |
| `apple/` | the macOS and iOS apps (`com.takeoutfm.mac`, `com.takeoutfm.ios`) | Xcode (project from XcodeGen), the core as a static framework from Gradle |
| `linux/` | the KDE app (`com.takeoutfm.linux`) and `tofm`, the terminal client | CMake + Ninja, the core as `libtakeout.so` from Gradle |
| `fdroid/` | publishing the Android apps to the F-Droid repository | a script |

## The shared core (`:shared`)

Everything that's the same on every platform: talking to the server, the rules, and what's kept
on the device. No UI and no playback.

| Package | Holds |
|---|---|
| `api/` | `TakeoutApi` (Ktor; bearer token, refresh on 401, JSON decoded off the caller's thread), `TakeoutSession`, `TakeoutUrls`, `DeviceCode`, `ResponseCache` (offline browsing) |
| `model/` | the server's JSON models (`@Serializable`) |
| `library/` | `TakeoutLibrary`: the library index and recommendation sets |
| `music/`, `video/`, `podcast/`, `guide/` | repositories and domain types per area; `video/` also has the audio-track ranking and labels |
| `progress/` | `TakeoutProgress` (watch and episode progress, synced) and `ProgressRules` |
| `listen/` | listen rules, `ListenReporter`, `ListenBrainzAccount`, `ListenSession` |
| `offline/` | `CachePolicy`, the cache purge's rule |
| `settings/` | `PlaySettings` |
| `store/` | `KeyValueStore` (plain and encrypted, per platform) |
| `db/` | the SQLDelight database |
| `util/` | sorting (`sortKey`, `listFlow`), formatting |

Rules for the core:

- **No platform code in `commonMain`**: no Android or `java.*` imports; kotlinx-datetime, not
  `java.time`. Platform pieces are `expect` / `actual` in `androidMain`, `appleMain`,
  `linuxMain` (and `nativeMain`, shared by Apple and Linux).
- **No dependency injection framework**: plain constructors; each app wires the graph
  (Hilt modules on Android, `TakeoutCore` on Apple, `LinuxCore` on Linux).
- **Never heavy work on the caller's thread**: sorting and mapping the library run on
  `Dispatchers.Default` (`listFlow`); JSON decodes off the main thread.
- Tests in `commonTest` (`kotlin.test`), run on the JVM and natively (`linuxX64Test`; on a Mac,
  `macosArm64Test`). HTTP code is tested with Ktor's `MockEngine`; the database with an
  in-memory driver.

### Targets

| Target | For | HTTP engine |
|---|---|---|
| Android (`androidMain`) | phone, TV, watch | OkHttp |
| `macosArm64`, `iosArm64`, `iosSimulatorArm64` (`appleMain`) | Mac/iOS, as a static `Shared` framework | Darwin |
| `linuxX64` (`linuxMain`) | the KDE app and tofm, as `libtakeout.so` | the host's (Qt Network), through the C API |

### Database

SQLDelight (decision [005](../decisions/005-sqldelight.md)), file `takeout.db`, in
`shared/src/commonMain/sqldelight/`: progress (`Progress.sq`), unsent listens (`Listens.sq`),
Recently Played (`History.sq`), Stream History (`StreamHistory.sq`), the response cache
(`ResponseCache.sq`). Schema version 3; every change needs a migration (`1.sqm`, `2.sqm`;
`MigrationTest`). Writes go through `DbWriter`: one at a time, in order, off the caller's
thread.

The Android apps' downloads index is Media3's own database; the Apple and Linux indexes are
JSON files ([downloads-and-cache.md](downloads-and-cache.md)).

### Secrets and settings

Tokens (session, ListenBrainz) go to an encrypted `KeyValueStore`: EncryptedSharedPreferences
on Android, the Keychain on Apple, the Secret Service on Linux (handed to the core as JSON
through the C API). Settings (`PlaySettings`) go to a plain store: SharedPreferences, the
user defaults, a JSON file.

## Per platform

### Android (phone, TV, watch)

- Kotlin, Jetpack Compose (Material 3; Compose for TV; Wear Compose), Hilt.
- `:android-media`: Media3 ExoPlayer from `TakeoutPlayerFactory` (the media token through a
  `ResolvingDataSource`, decoder fallback), the background `TakeoutMusicService` (a
  `MediaLibraryService`; each app subclasses it), `MusicPlayer` (the UI's `MediaController`),
  `ListenTracker`, downloads and the cache, video track helpers, Android Auto's browse tree.
  Plain constructors, no Hilt.
- Each app provides the core and `:android-media` in its own Hilt module (`AppModule`,
  `TakeoutModule`, `WatchModule`).
- Min SDK 28 (watch 30); one version for the three apps (`gradle.properties`), all signed
  with `android/key.properties`.

### Mac and iOS (`apple/`)

- SwiftUI; AVFoundation for music; VLCKit / MobileVLCKit for video (MKV and its tracks).
- The core is the static `Shared` framework. `TakeoutCore` builds the shared graph;
  `SwiftCalls` wraps the suspend functions Swift calls (`@Throws`, or a Kotlin exception
  crashes instead of throwing); `WatchKt.watch` follows a Flow on the main thread. No SKIE.
- Video streams through `StreamProxy`, a loopback HTTP server: VLC can't recover when a
  presigned storage URL expires mid-film, so the proxy fetches fresh ones and VLC never sees
  the token.
- The Kotlin compiles on Linux (`compileKotlinMacosArm64`); linking and the Swift need a Mac.
  `apple/README.md` is the build guide.

### Linux: the KDE app and tofm (`linux/`)

- C++ and Qt 6. The KDE app: QML with Kirigami and KDE Frameworks (KI18n, CoreAddons,
  DBusAddons, Crash, ColorScheme, Kirigami Addons). tofm: FTXUI in a terminal.
- **The C API** (`linux/src/takeout.h`; `linuxMain` `linux/Bridge.kt`, `LinuxCalls`), four
  functions: `takeout_init`, `takeout_call(name, argsJson, callback)` (runs on a coroutine,
  answers once on a worker thread), `takeout_watch(name, callback)` / `takeout_unwatch` for
  flows. Results are JSON of the shared types. The host also lends its HTTP
  (`takeout_set_http`): the core's requests go through Qt Network.
- `src/core/` (Qt Core only, the static library `takeoutcore`, shared by both apps):
  `TakeoutCore` (calls with callbacks, watched state as Qt properties), `Player` (music on
  libmpv), `Mpris`, `SecretStore`. Player position stays in C++ and never crosses the bridge.
- The KDE app's `src/VideoView`: its own mpv rendering into Qt Quick; tofm plays video in mpv
  as a program of its own.
- No JVM (decision [006](../decisions/006-linux-native.md)); everything builds, runs and is
  tested on the development machine. Packaged as three debs (`cpack`).
