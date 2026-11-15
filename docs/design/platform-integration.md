# Platform integration — design

Requirements: [../requirements/platform-integration.md](../requirements/platform-integration.md).

- **Android Auto**: `TakeoutMusicService` is a `MediaLibraryService` browsing `:android-media`
  `auto/TakeoutBrowseTree` (the phone provides it; the TV gives none). Tested by
  `android/src/androidTest` `BrowseTreeTest`, run with `am instrument` (not
  `connectedAndroidTest`, which uninstalls the app and its sign-in).
- **Phone voice**: `voice/VoiceRequests` turns `MEDIA_PLAY_FROM_SEARCH` and
  `com.takeoutfm.action.*` intents into server search refs; `VoiceHandler` (from
  `MainActivity`) plays them with `TakeoutApi.resolveRef`, which rewrites the user's
  server-side current playlist.
- **Watch**: `tile/PlayerTileService` and a complication on `MusicPlayer`'s state.
- **Mac**: `MenuBarExtra` in `macOS/TakeoutFMApp.swift`.
- **Linux**: `src/core/Mpris` (bus name and identity are parameters: `takeoutfm`, `tofm`);
  KDE Frameworks KCrash, ColorScheme (`KColorSchemeManager`), DBusAddons (single instance).
- **tofm**: its own D-Bus service `com.takeoutfm.tofm` (`/tofm`: `Play(kind, words)`,
  `Status()`); the search as the Search page (GET, no change to the server's playlist).
