# Sign-in and session — design

Requirements: [../requirements/sign-in.md](../requirements/sign-in.md).

## Shared (`:shared` `api/`)

- `TakeoutApi.requestCode` (`GET /api/code`) gives a `DeviceCode` (code, `link` =
  `<endpoint>/link`, expiry by this device's clock, poll interval); `awaitCode` polls
  (`POST /api/code` with the code's token) until it's approved, expired or refused.
- `TakeoutApi.login(endpoint, user, password, passcode)` (`POST /api/token`) for the phone's
  password sign-in; `link(endpoint, code, user, password, passcode)` approves another device.
- `TakeoutSession` holds the endpoint, the three tokens (access, refresh, media) and the user's
  name, in a `KeyValueStore` that must be encrypted (null: memory only). `signIn`,
  `updateTokens` (after a refresh; the media token stays), `setUser`, `signOut`.
- `TakeoutApi` sends the access token; on 401 it refreshes once (`refreshTokens`) and retries;
  a refused refresh signs out.
- `TakeoutSession.mediaAuthorization(url)`: "Bearer <media token>" only for URLs on the
  signed-in server, else null.
- `TakeoutLibrary.refresh` asks `/api/me` for the user's name.

## Per platform

| App | Store | Sign-in UI |
|---|---|---|
| Phone | `AndroidStores` encrypted (`takeout_session`) | `ui/signin/` (password, or a code); `ui/settings/LinkScreen` |
| TV | the same, in `TakeoutModule` | `screens/login/` |
| Watch | the same, in `WatchModule` | `WatchApp` sign-in |
| Mac/iOS | `KeychainStore` (`DebugTokenStore` in debug runs) | `SignInView.swift` |
| Linux | `SecretStore` (libsecret, item per app: `takeoutfm`) | `SignInPage.qml` |
| tofm | `SecretStore`, its own item | the sign-in screen in `Tofm.cpp` |
