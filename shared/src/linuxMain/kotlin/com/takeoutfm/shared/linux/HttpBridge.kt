@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.takeoutfm.shared.linux

import com.takeoutfm.shared.api.HostHead
import com.takeoutfm.shared.api.HostHttp
import com.takeoutfm.shared.api.HostRequest
import com.takeoutfm.shared.api.HttpHandleFunction
import com.takeoutfm.shared.api.HttpStartFunction
import com.takeoutfm.shared.api.hostError
import com.takeoutfm.shared.api.hostHeaders
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString

// The host's side of HTTP (api/HostEngine.kt; linux/src/takeout.h declares these). For each
// request the core calls the host's start(ctx, handle, requestJson, body, length); the host
// answers on any thread with takeout_http_head, takeout_http_data (as often as it likes), and
// takeout_http_end exactly once — also after the core called its cancel. After end the handle
// is gone: the host ignores a cancel or resume for a request it has ended.

/**
 * Registers the host's HTTP, before takeout_init: [start] for each request (the request as
 * JSON — method, url, headers as [[name, value]…], connectTimeoutMs, socketTimeoutMs — and its
 * body, which [start] must copy before returning), [cancel] to abort one, [resume] to go on
 * after takeout_http_data asked for a pause. Called on the core's threads.
 */
@CName("takeout_set_http")
fun takeoutSetHttp(start: HttpStartFunction?, cancel: HttpHandleFunction?, resume: HttpHandleFunction?, ctx: COpaquePointer?) {
    HostHttp.context = ctx
    HostHttp.cancel = cancel
    HostHttp.resume = resume
    HostHttp.start = start
}

/** The response's [status] and headers ([[name, value]…]); before any body. */
@CName("takeout_http_head")
fun takeoutHttpHead(handle: COpaquePointer?, status: Int, headersJson: CPointer<ByteVar>?) {
    val request = handle?.asStableRef<HostRequest>()?.get() ?: return
    request.head.complete(HostHead(status, hostHeaders(headersJson?.toKString())))
}

/**
 * The next [length] bytes of the body (copied before this returns). Returns 1 when the host
 * should stop reading until the core calls its resume (the client is behind), else 0.
 */
@CName("takeout_http_data")
fun takeoutHttpData(handle: COpaquePointer?, bytes: COpaquePointer?, length: ULong): Int {
    val request = handle?.asStableRef<HostRequest>()?.get() ?: return 0
    if (bytes == null || length == 0uL) return 0
    return if (request.data(bytes.reinterpret<ByteVar>().readBytes(length.toInt()))) 1 else 0
}

/** The response is over: [errorJson] null when it all arrived, else {kind, message}. */
@CName("takeout_http_end")
fun takeoutHttpEnd(handle: COpaquePointer?, errorJson: CPointer<ByteVar>?) {
    val request = handle?.asStableRef<HostRequest>()?.get() ?: return
    request.end(errorJson?.toKString()?.let(::hostError))
}
