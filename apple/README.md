# TakeoutFM for macOS

A native SwiftUI app on top of the Kotlin shared core (`:shared`), linked as the static
`Shared` framework. Music plays with AVFoundation (FLAC, MP3 and M4A; the media token goes in
the Authorization header). Video (MKV with embedded audio and subtitle tracks, which
AVFoundation can't open) will play with VLCKit. Plan and status: `PLAN.md` → Apple.

**Status:** builds and launches on the Mac (Xcode 27, first built 2026-10-27). It has:
device-code sign-in; a sidebar with Recently Added, New Releases, Recently Played, Artists,
Favorites, Playlists, Radio and Podcasts (podcast and episode pages with show notes; episodes resume where they
were left, synced); search from the toolbar; album, artist and playlist pages with
♥ for tracks; and an AVQueuePlayer queue with a Now Playing bar (a LIVE layout with the song
playing for streams), the menu's Playback commands and the system's Now Playing (media keys,
Control Center); Movies and TV pages, and a VLCKit video window (audio and subtitle tracks,
resume, saved progress, next episode). Downloads (albums, playlists, favorites, episodes, movies and TV episodes; they play
offline) with a Downloads page. A full Now Playing screen (⌘L) with the queue, Play Next / Add to Queue on tracks, repeat, a
menu bar player, and Settings for the theme (System / Light / Dark), the menu bar
item, Track Activity and ListenBrainz. Plays are reported to the server's activity (Settings →
Track Activity, on by default as in the other apps), so playing music here changes your real
stats. The app icon is rendered by `tools/render-icon.swift` (see its header).

## Mac setup (once)

1. Install **Xcode** from the App Store, open it once to finish installing, then:
   ```
   sudo xcode-select -s /Applications/Xcode.app
   ```
2. Install [Homebrew](https://brew.sh), then a JDK for Gradle and XcodeGen (step 4 fetches VLCKit):
   ```
   brew install openjdk@17 xcodegen
   sudo ln -sfn "$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-17.jdk
   ```
   (The link lets `/usr/libexec/java_home -v 17` find it; without it the Xcode build step
   falls back to Homebrew's `openjdk@17` directly.)
3. Get the code. There's no git remote; either clone straight from the Linux machine over SSH
   (it needs an SSH server running):
   ```
   git clone ssh://boyns@<linux-host>/home/boyns/projects/takeout_native
   ```
   and later `git pull` the same way, or push the repo to a private GitHub repository and
   clone that on both machines.

4. Fetch VLCKit (VideoLAN's prebuilt macOS framework, 411 MB, LGPL 2.1; not committed):
   ```
   apple/fetch-vlckit.sh
   ```

No Android SDK is needed on the Mac: building `:shared` for macOS doesn't touch it.

## Build and run

```
cd takeout_native/apple
xcodegen                 # writes TakeoutFM.xcodeproj from project.yml (not committed)
open TakeoutFM.xcodeproj # then Run (⌘R)
```

or from the command line:

```
xcodebuild -project TakeoutFM.xcodeproj -scheme TakeoutFM -configuration Debug build
```

The build's first step runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode`, which
compiles the Kotlin core for macOS (the first time it downloads the Kotlin/Native toolchain,
a few minutes). Run `xcodegen` again after adding or removing Swift files.

## How it fits together

- `TakeoutCore` (Kotlin, `shared/src/appleMain/…/apple/`) creates the shared classes once, as
  the Android apps' Hilt modules do. The session and ListenBrainz tokens go in the Keychain
  (`KeychainStore.swift`, implementing the shared `KeyValueStore`); other saved values in the
  user defaults (`UserDefaultsStore`); the database in Application Support/TakeoutFM.
- `SwiftCalls` wraps the suspend functions Swift calls with `@Throws`: without it a Kotlin
  exception (a network error) crashes the app instead of throwing in Swift. Add new calls
  there. Kotlin/Native prefixes names that start with `new`, `copy`, `alloc` or `init` with
  `do` (Objective-C ownership rules), so `newReleases()` is `doNewReleases()` in Swift.
- Debug builds differ in two ways: tokens go in `debug-tokens.json` in Application Support
  (`DebugTokenStore`), since each ad-hoc-signed rebuild counts as a new app to the Keychain,
  which then asks for the login password; and `TakeoutFM -playStations st-144,st-143` (the
  binary in `Contents/MacOS`) plays each station for 6 seconds, with the player's state in
  the log, to test playback without the UI.
- Downloads: `MediaCache` keeps whole files by ETag (stream URLs redirect to expiring
  presigned ones) in Application Support/TakeoutFM/Media, with `downloads.json` saying what
  each is and what it came with. A download resolves the presigned URL with the token
  (`StreamResolver`) and fetches it without, two at a time; interrupted ones restart at
  launch. `AudioPlayer` and `VideoPlayerModel` play the file when there is one. Debug builds
  take `-downloadAlbum al-…` and `-playAlbum al-…`.
- Listens: `AudioPlayer` tells the shared `ListenSession` (`SwiftCalls.listenSession()`)
  when the item changes, playback starts or stops, every half second, and each ICY title
  (from an `AVPlayerItemMetadataOutput` on stream items); it decides and reports through
  `ListenReporter`, which keeps failures and is retried on launch and on activate.
- Video: `VideoPlayerModel` (VLCKit) plays in its own window (`WindowGroup` "video", opened
  with a `VideoPlayRequest`). VLC can't send the media token as a header, and after a
  redirect it keeps requesting (and seeking with) the presigned storage URL, which expires
  after 15 minutes. So VLC plays from `StreamProxy`, an HTTP server on 127.0.0.1 (the
  `network.server` entitlement): each video gets a random path while it's open, and every
  range VLC asks for is fetched from storage with a presigned URL from `StreamResolver` (the
  location requested with the token, redirects not followed), reused for 10 minutes and
  fetched again if storage answers 403. The bytes pass through the app on loopback; neither
  the token nor a presigned URL reaches VLC. Progress goes through the shared
  `TakeoutProgress` (first save 10 s in, then every 10 s, on pause and on close), so playing
  a title changes your real watch progress. Debug builds take `-playVideo e-57` (and
  `-noProgress YES` to save nothing) to test playback without the UI.
- `WatchKt.watch(flow:onEach:)` follows a Kotlin Flow on the main thread (e.g. the session
  state); `CodeSignIn` runs the device-code sign-in.
- Shared logic is tested on the JVM on Linux (`./gradlew :shared:testDebugUnitTest`), and the
  macOS klibs compile there too (`./gradlew :shared:compileKotlinMacosArm64`), so Kotlin
  changes can be checked without the Mac. Linking and all Swift need the Mac. On the Mac, also
  run `./gradlew :shared:macosArm64Test`: Kotlin/Native differs from the JVM in places (its
  `Regex` rejects Unicode classes like `\p{L}`, and a failed top-level initializer breaks
  every function in that file with `FileFailedToInitializeException`).
