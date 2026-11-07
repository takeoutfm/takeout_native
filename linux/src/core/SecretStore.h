#pragma once

#include <string>

// The core's secrets (session and ListenBrainz tokens, one JSON object) in the Secret Service:
// KWallet on Plasma, GNOME Keyring elsewhere. Plain C++, so libsecret's GLib headers never
// meet Qt's keyword macros.
namespace SecretStore {
/** [app]'s saved JSON ("takeoutfm", "tofm"), or "" when there's none (or no Secret Service). */
std::string load(const std::string &app);
/** Replaces [app]'s saved JSON; called from the core's threads. */
void save(const std::string &app, const char *json);

/**
 * Where secrets go when there's no Secret Service to keep them (tofm over SSH, a headless
 * box): a file only the user can read. "" (the default, the KDE app) for none.
 */
void setFallbackFile(const std::string &path);
/** The fallback file is in use: there was no Secret Service. */
bool usingFallback();
}
