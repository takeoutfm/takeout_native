# Settings

What every app's settings hold, and what's particular to one. Each setting's behaviour is in
its own area; this is the list. Settings are kept on the device, per app.

| Setting | Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|---|
| Server, Connected as, Sign out | SIGN-9/10 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Track Activity | LISTEN-4 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| ListenBrainz: connect, submit, disconnect | LISTEN-5/7/8 | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Playback Caching, Cache size, Clear cached | CACHE-16 | ✅ | — | ✅ | 🔨 | ✅ | ✅ |
| Theme: System, Light, Dark | SET-1 | ✅ | — | — | ✅ | ✅ (Breeze) | — |
| Version, About | SET-2 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

- **SET-1** The theme follows the system unless set; it applies to the whole app, status bar
  icons included.
- **SET-2** The version shows in the settings or About.

Particular to one app:

- **Watch**: stream over the watch's own mobile data, not only Wi-Fi.
- **TV**: how far back Continue Watching looks.
- **Mac**: show the player in the menu bar.
- **tofm**: album art (drawn with block characters), the cover in the Now Playing bar (off over
  SSH by default), the video player command (mpv by default; another gets downloads only).

The phone's settings are `:shared` `PlaySettings`, as are the watch's, Mac/iOS's and Linux's
(each in its own store); the TV keeps its own (`TakeoutSettings`).
