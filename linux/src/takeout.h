// The C API of libtakeout.so, the shared Kotlin core (shared/src/linuxMain/.../linux/Bridge.kt
// documents each function). Strings are UTF-8 JSON; those handed to a callback are only valid
// during the call. Callbacks run on the core's background threads.
#pragma once

#include <stddef.h>

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


// ---- HTTP through the host (shared/src/linuxMain/.../linux/HttpBridge.kt, PLAN.md L8) ----
// The core makes its HTTP requests through the host app (its TLS, its proxy settings). For each
// one it calls start(ctx, handle, request_json, body, length) on its own threads: the request
// is {method, url, headers: [[name, value]…], connectTimeoutMs?, socketTimeoutMs?}, and the body
// must be copied before start returns. The host never follows redirects. It answers, on any
// thread, with takeout_http_head, then takeout_http_data as the body comes, then
// takeout_http_end exactly once (also after the core called cancel); after end the handle is
// gone, so the host ignores a cancel or resume for a request it has ended.

typedef void (*takeout_http_start_fn)(void *ctx, void *handle, const char *request_json, const void *body, size_t length);
typedef void (*takeout_http_handle_fn)(void *ctx, void *handle);

/** Registers the host's HTTP; before takeout_init. resume: go on after takeout_http_data said 1. */
void takeout_set_http(takeout_http_start_fn start, takeout_http_handle_fn cancel, takeout_http_handle_fn resume, void *ctx);
/** The response status and headers ([[name, value]…]), before any body. */
void takeout_http_head(void *handle, int status, const char *headers_json);
/** The next bytes of the body (copied before it returns). Returns 1: stop reading until resume. */
int takeout_http_data(void *handle, const void *bytes, size_t length);
/** The end of the response: error_json NULL when complete, else {kind: network|timeout|cancelled, message}. */
void takeout_http_end(void *handle, const char *error_json);

#ifdef __cplusplus
}
#endif
