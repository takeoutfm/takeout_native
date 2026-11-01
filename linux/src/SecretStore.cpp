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

std::string SecretStore::load()
{
    GError *error = nullptr;
    gchar *value = secret_password_lookup_sync(schema(), nullptr, &error, "app", "takeoutfm", nullptr);
    if (error) {
        g_warning("TakeoutFM: can't read the Secret Service: %s", error->message);
        g_error_free(error);
    }
    if (!value) return {};
    std::string json(value);
    secret_password_free(value);
    return json;
}

void SecretStore::save(const char *json)
{
    GError *error = nullptr;
    secret_password_store_sync(schema(), SECRET_COLLECTION_DEFAULT, "TakeoutFM", json, nullptr, &error,
                               "app", "takeoutfm", nullptr);
    if (error) {
        g_warning("TakeoutFM: can't save to the Secret Service: %s", error->message);
        g_error_free(error);
    }
}
