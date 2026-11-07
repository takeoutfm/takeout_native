#include "SecretStore.h"

#include <atomic>
#include <cstdio>
#include <fcntl.h>
#include <fstream>
#include <libsecret/secret.h>
#include <mutex>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

namespace {
const SecretSchema *schema()
{
    static const SecretSchema s = {
        "com.takeoutfm.linux", SECRET_SCHEMA_NONE,
        {{"app", SECRET_SCHEMA_ATTRIBUTE_STRING}, {nullptr, SECRET_SCHEMA_ATTRIBUTE_STRING}},
        0, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    return &s;
}

std::string fallbackFile;
std::atomic<bool> fallingBack = false;
std::mutex fileLock;

std::string readFile()
{
    std::lock_guard lock(fileLock);
    std::ifstream in(fallbackFile);
    std::stringstream text;
    text << in.rdbuf();
    return text.str();
}

/** Written whole to a new file (0600 from the start), then renamed over the old one. */
void writeFile(const char *json)
{
    std::lock_guard lock(fileLock);
    const std::string part = fallbackFile + ".part";
    const int fd = ::open(part.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        g_warning("can't write %s", part.c_str());
        return;
    }
    ::fchmod(fd, 0600);
    const std::string text(json);
    const bool written = ::write(fd, text.data(), text.size()) == ssize_t(text.size());
    ::close(fd);
    if (written) std::rename(part.c_str(), fallbackFile.c_str());
}
}

void SecretStore::setFallbackFile(const std::string &path) { fallbackFile = path; }

bool SecretStore::usingFallback() { return fallingBack; }

std::string SecretStore::load(const std::string &app)
{
    GError *error = nullptr;
    gchar *value = secret_password_lookup_sync(schema(), nullptr, &error, "app", app.c_str(), nullptr);
    if (error) {
        g_warning("%s: can't read the Secret Service: %s", app.c_str(), error->message);
        g_error_free(error);
        if (!fallbackFile.empty()) {
            fallingBack = true;
            return readFile();
        }
    }
    if (!value) return {};
    std::string json(value);
    secret_password_free(value);
    return json;
}

void SecretStore::save(const std::string &app, const char *json)
{
    if (fallingBack) return writeFile(json);
    GError *error = nullptr;
    // The label is what KWallet / Seahorse list: "TakeoutFM" for the KDE app (as before),
    // else the app's name.
    const std::string label = app == "takeoutfm" ? "TakeoutFM" : app;
    secret_password_store_sync(schema(), SECRET_COLLECTION_DEFAULT, label.c_str(), json, nullptr, &error,
                               "app", app.c_str(), nullptr);
    if (error) {
        g_warning("%s: can't save to the Secret Service: %s", app.c_str(), error->message);
        g_error_free(error);
        if (!fallbackFile.empty()) {
            fallingBack = true;
            writeFile(json);
        }
    }
}
