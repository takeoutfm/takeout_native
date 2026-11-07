#include "SecretStore.h"

#include <libsecret/secret.h>

namespace {
const SecretSchema *schema()
{
    static const SecretSchema s = {
        "com.takeoutfm.linux", SECRET_SCHEMA_NONE,
        {{"app", SECRET_SCHEMA_ATTRIBUTE_STRING}, {nullptr, SECRET_SCHEMA_ATTRIBUTE_STRING}},
        0, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    return &s;
}
}

std::string SecretStore::load(const std::string &app)
{
    GError *error = nullptr;
    gchar *value = secret_password_lookup_sync(schema(), nullptr, &error, "app", app.c_str(), nullptr);
    if (error) {
        g_warning("%s: can't read the Secret Service: %s", app.c_str(), error->message);
        g_error_free(error);
    }
    if (!value) return {};
    std::string json(value);
    secret_password_free(value);
    return json;
}

void SecretStore::save(const std::string &app, const char *json)
{
    GError *error = nullptr;
    // The label is what KWallet / Seahorse list: "TakeoutFM" for the KDE app (as before),
    // else the app's name.
    const std::string label = app == "takeoutfm" ? "TakeoutFM" : app;
    secret_password_store_sync(schema(), SECRET_COLLECTION_DEFAULT, label.c_str(), json, nullptr, &error,
                               "app", app.c_str(), nullptr);
    if (error) {
        g_warning("%s: can't save to the Secret Service: %s", app.c_str(), error->message);
        g_error_free(error);
    }
}
