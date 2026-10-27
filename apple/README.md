# TakeoutFM for macOS

A native SwiftUI app on top of the Kotlin shared core (`:shared`), linked as the static
`Shared` framework. Music plays with AVFoundation (FLAC, MP3 and M4A; the media token goes in
the Authorization header). Video (MKV with embedded audio and subtitle tracks, which
AVFoundation can't open) will play with VLCKit. Plan and status: `PLAN.md` → Apple.

**Status:** the skeleton was written on Linux and hasn't been compiled yet, so expect small
fixes on the first build. It has: device-code sign-in, a sidebar with Recently Added and New
Releases, album pages, and an AVQueuePlayer queue with a Now Playing bar, the menu's Playback
commands and the system's Now Playing (media keys, Control Center).

## Mac setup (once)

1. Install **Xcode** from the App Store, open it once to finish installing, then:
   ```
   sudo xcode-select -s /Applications/Xcode.app
   ```
2. Install [Homebrew](https://brew.sh), then a JDK for Gradle and XcodeGen:
   ```
   brew install openjdk@17 xcodegen
   sudo ln -sfn "$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-17.jdk
   ```
   (The link lets `/usr/libexec/java_home -v 17`, which the Xcode build step uses, find it.)
3. Get the code. There's no git remote; either clone straight from the Linux machine over SSH
   (it needs an SSH server running):
   ```
   git clone ssh://boyns@<linux-host>/home/boyns/projects/takeout_native
   ```
   and later `git pull` the same way, or push the repo to a private GitHub repository and
   clone that on both machines.

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
  there.
- `WatchKt.watch(flow:onEach:)` follows a Kotlin Flow on the main thread (e.g. the session
  state); `CodeSignIn` runs the device-code sign-in.
- Shared logic is tested on the JVM on Linux (`./gradlew :shared:testDebugUnitTest`), and the
  macOS klibs compile there too (`./gradlew :shared:compileKotlinMacosArm64`), so Kotlin
  changes can be checked without the Mac. Linking and all Swift need the Mac.
