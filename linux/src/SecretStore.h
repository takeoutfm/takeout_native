#pragma once

#include <string>

// The core's secrets (session and ListenBrainz tokens, one JSON object) in the Secret Service:
// KWallet on Plasma, GNOME Keyring elsewhere. Plain C++, so libsecret's GLib headers never
// meet Qt's keyword macros.
namespace SecretStore {
/** The saved JSON, or "" when there's none (or no Secret Service). */
std::string load();
/** Replaces the saved JSON; called from the core's threads. */
void save(const char *json);
}
