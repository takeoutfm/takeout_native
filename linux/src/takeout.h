// The C API of libtakeout.so, the shared Kotlin core (shared/src/linuxMain/.../linux/Bridge.kt
// documents each function). Strings are UTF-8 JSON; those handed to a callback are only valid
// during the call. Callbacks run on the core's background threads.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*takeout_value_cb)(void *ctx, const char *json);
typedef void (*takeout_result_cb)(void *ctx, const char *result_json, const char *error_json);

/** Creates the core once; 0 on success. [secrets] is the saved JSON (or NULL); [save_secrets]
 *  gets the new JSON after every change, to keep in the Secret Service. */
int takeout_init(const char *data_dir, const char *user_agent, const char *secrets,
                 takeout_value_cb save_secrets, void *ctx);

/** Runs call [name] with [args_json] (an object, or NULL); [cb] is called once. */
void takeout_call(const char *name, const char *args_json, takeout_result_cb cb, void *ctx);

/** Follows shared state [name] (session, library, favoriteTracks, recentlyPlayed,
 *  listenBrainz, settings); NULL for an unknown name. */
void *takeout_watch(const char *name, takeout_value_cb cb, void *ctx);

/** Stops a watch; [cb] isn't called after this returns. Not from inside [cb]. */
void takeout_unwatch(void *handle);

#ifdef __cplusplus
}
#endif
