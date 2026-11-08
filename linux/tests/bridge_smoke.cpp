// Loads libtakeout.so and makes one call through the C API, as the app will.
#include "takeout.h"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <future>
#include <string>

int main()
{
    char dir[] = "/tmp/takeout-smoke-XXXXXX";
    // A host that has no network: every request ends at once with an error (the core's HTTP
    // goes through the host, PLAN.md L8).
    takeout_set_http(
        [](void *, void *handle, const char *, const void *, size_t) {
            takeout_http_end(handle, R"({"kind":"network","message":"no network in the smoke test"})");
        },
        [](void *, void *) {}, [](void *, void *) {}, nullptr);
    if (!mkdtemp(dir) || takeout_init(dir, "TakeoutFM-smoke", nullptr, nullptr, nullptr) != 0) {
        std::fprintf(stderr, "takeout_init failed\n");
        return 1;
    }

    std::promise<std::string> answer;
    takeout_call("version", nullptr, [](void *ctx, const char *result, const char *error) {
        static_cast<std::promise<std::string> *>(ctx)->set_value(result ? result : std::string("error ") + error);
    }, &answer);
    auto version = answer.get_future();
    if (version.wait_for(std::chrono::seconds(10)) != std::future_status::ready) {
        std::fprintf(stderr, "no answer\n");
        return 1;
    }
    std::string value = version.get();
    std::printf("version %s\n", value.c_str());

    void *watch = takeout_watch("session", [](void *, const char *) {}, nullptr);
    if (!watch) {
        std::fprintf(stderr, "takeout_watch failed\n");
        return 1;
    }
    takeout_unwatch(watch);
    std::filesystem::remove_all(dir);
    return value == "1" ? 0 : 1;
}
