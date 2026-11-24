# Sign-in and session

How an app signs in to a Takeout server, keeps the session and signs out. Design:
[../design/sign-in.md](../design/sign-in.md).

## Signing in

- **SIGN-1** Every app must sign in with a **device code**: the viewer enters the server, the
  app shows a code and where to approve it (`<server>/link`, on any signed-in device), and
  signs in by itself once it's approved. No password is typed on the device.
- **SIGN-2** The code's time left shows; once it expires the app says so and offers a new one.
- **SIGN-3** The phone also signs in with **user, password and passcode** (the passcode only
  when the account uses two-factor codes), as the Flutter app did.
- **SIGN-4** The server field starts with the last server used (or `https://takeout.fm`).
- **SIGN-5** A server that can't be reached, or gives no code, says so in the sign-in screen.

## The session

- **SIGN-6** Tokens are kept in the platform's secure store, never in plain files.
- **SIGN-7** Requests use the access token, refreshed by itself when the server answers 401;
  when the refresh itself is refused, the app signs out.
- **SIGN-8** Streams use the separate **media token**, sent only to URLs on the signed-in
  server, never to storage or other hosts it redirects to.
- **SIGN-9** The signed-in user's name (from the server) shows in the settings as "Connected
  as"; offline or on servers without it, nothing shows.
- **SIGN-10** Signing out forgets the tokens and asks first.

## Linking another device

- **SIGN-11** An app with a keyboard should offer **Link**: approve another device's code
  from here (with this account's password and passcode), as the server's `/link` page does.

## Status

| Rule | Phone | TV | Watch | Mac/iOS | Linux | tofm |
|---|---|---|---|---|---|---|
| SIGN-1/2 device code | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| SIGN-4 last server, else takeout.fm | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| SIGN-5 says when it can't reach the server | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| SIGN-3 password and passcode | ✅ | — | — | — | — | — |
| SIGN-6 secure store | ✅ encrypted prefs | ✅ encrypted prefs | ✅ encrypted prefs | ✅ Keychain | ✅ Secret Service | ✅ Secret Service (own item) |
| SIGN-7/8 tokens | ✅ shared core | ✅ | ✅ | ✅ | ✅ | ✅ |
| SIGN-9 Connected as | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| SIGN-10 asks before signing out | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| SIGN-11 Link | ✅ | — | — | ✅ (approving a real code not yet seen) | ✅ Settings (approving a real code not yet seen) | ✅ Settings, as prompts (the same) |

