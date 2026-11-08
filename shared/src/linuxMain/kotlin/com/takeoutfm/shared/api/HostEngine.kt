@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, io.ktor.utils.io.InternalAPI::class)

package com.takeoutfm.shared.api

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.callContext
import io.ktor.client.engine.mergeHeaders
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// HTTP on Linux goes through the host app (Qt Network, the system's TLS) instead of a curl and
// OpenSSL compiled into libtakeout.so (PLAN.md §12, L8). The host registers its functions with
// takeout_set_http (linux/HttpBridge.kt); this engine hands it each request and streams the
// answer back. The engine never follows redirects: Ktor's plugin, or MediaDownloads, decides,
// so the rule that the media token goes only to Takeout stays in shared code.

/** `void start(void* ctx, void* handle, const char* requestJson, const void* body, size_t length)`. */
typealias HttpStartFunction =
    CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<ByteVar>?, COpaquePointer?, ULong) -> Unit>>

/** `void cancel(void* ctx, void* handle)`, and the same for resume. */
typealias HttpHandleFunction = CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>

/** The host's functions (takeout_set_http), set once before the core makes requests. */
internal object HostHttp {
    var start: HttpStartFunction? = null
    var cancel: HttpHandleFunction? = null
    var resume: HttpHandleFunction? = null
    var context: COpaquePointer? = null
}

/** The status line and headers, once the host has them. */
internal class HostHead(val status: Int, val headers: Headers)

/**
 * One request in flight, known to the host by [handle] (a [StableRef] to this). The host feeds
 * [head], then [chunks]; [ended] once it has called takeout_http_end, after which the handle
 * is gone.
 */
internal class HostRequest {
    val head = CompletableDeferred<HostHead>()
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    /** Bytes the host has given that the client hasn't read yet. */
    val buffered = AtomicLong(0)
    val paused = AtomicInt(0)
    val ended = AtomicInt(0)
    var ref: StableRef<HostRequest>? = null
    val handle: COpaquePointer get() = ref!!.asCPointer()

    /** A chunk from the host; true when it should pause until resumed. */
    fun data(bytes: ByteArray): Boolean {
        chunks.trySend(bytes)
        if (buffered.addAndGet(bytes.size.toLong()) <= PauseAbove) return false
        paused.value = 1
        return true
    }

    /** The host is done: [error] null when the body is complete. */
    fun end(error: IOException?) {
        if (!ended.compareAndSet(0, 1)) return
        if (error != null) head.completeExceptionally(error)
        else if (!head.isCompleted) head.completeExceptionally(IOException("No response"))
        chunks.close(error)
        ref?.dispose()
    }

    companion object {
        /** The host pauses past this much unread, and resumes when it's down to [ResumeBelow]. */
        const val PauseAbove = 1L shl 20
        const val ResumeBelow = 256L shl 10
    }
}

internal val hostJson = Json { ignoreUnknownKeys = true }

/** A host error's JSON ({kind, message}) as the IOException the core treats as "offline". */
internal fun hostError(json: String): IOException {
    val error = runCatching { hostJson.parseToJsonElement(json).jsonObject }.getOrNull()
    val kind = error?.get("kind")?.jsonPrimitive?.content ?: "network"
    val message = error?.get("message")?.jsonPrimitive?.content.orEmpty()
    return IOException(if (message.isEmpty()) kind else "$kind: $message")
}

/** "[[name, value], …]" as headers (a name may repeat). */
internal fun hostHeaders(json: String?): Headers {
    val builder = HeadersBuilder()
    val list = json?.let { runCatching { hostJson.parseToJsonElement(it).jsonArray }.getOrNull() }.orEmpty()
    for (pair in list) {
        val parts = (pair as? JsonArray) ?: continue
        if (parts.size == 2) builder.append(parts[0].jsonPrimitive.content, parts[1].jsonPrimitive.content)
    }
    return builder.build()
}

class HostEngineConfig : HttpClientEngineConfig()

/** Ktor's engine over the host's HTTP. */
class HostEngine(override val config: HostEngineConfig) : HttpClientEngineBase("takeout-host") {
    override val dispatcher: CoroutineDispatcher = Dispatchers.IO
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(HttpTimeoutCapability)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val start = HostHttp.start ?: throw IOException("No HTTP: takeout_set_http wasn't called")
        val callContext = callContext()
        val body = data.body.bytes()

        val headers = JsonArray(buildList {
            mergeHeaders(data.headers, data.body) { name, value -> add(JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(value)))) }
        })
        val timeout = data.getCapabilityOrNull(HttpTimeoutCapability)
        val request = buildJsonObject {
            put("method", data.method.value)
            put("url", data.url.toString())
            put("headers", headers)
            timeout?.connectTimeoutMillis?.let { put("connectTimeoutMs", it) }
            timeout?.socketTimeoutMillis?.let { put("socketTimeoutMs", it) }
        }.toString()

        val host = HostRequest()
        host.ref = StableRef.create(host)
        val handle = host.handle
        // Whenever the call ends (done, abandoned or cancelled) before the host has: stop it.
        callContext[Job]?.invokeOnCompletion {
            if (host.ended.value == 0) HostHttp.cancel?.invoke(HostHttp.context, handle)
        }
        memScoped {
            if (body.isEmpty()) start(HostHttp.context, handle, request.cstr.ptr, null, 0u)
            else body.usePinned { start(HostHttp.context, handle, request.cstr.ptr, it.addressOf(0), body.size.toULong()) }
        }

        val head = host.head.await()
        // The body as it comes; when the client has caught up, the host may go on.
        val content = CoroutineScope(callContext).writer(Dispatchers.Unconfined) {
            for (chunk in host.chunks) {
                channel.writeFully(chunk)
                channel.flush()
                val left = host.buffered.addAndGet(-chunk.size.toLong())
                if (left < HostRequest.ResumeBelow && host.ended.value == 0 && host.paused.compareAndSet(1, 0))
                    HostHttp.resume?.invoke(HostHttp.context, handle)
            }
        }.channel
        return HttpResponseData(
            HttpStatusCode.fromValue(head.status), GMTDate(), head.headers, HttpProtocolVersion.HTTP_1_1, content, callContext,
        )
    }
}

object Host : HttpClientEngineFactory<HostEngineConfig> {
    override fun create(block: HostEngineConfig.() -> Unit) = HostEngine(HostEngineConfig().apply(block))
}

/** A request body as bytes (they're small: JSON, forms). */
private suspend fun OutgoingContent.bytes(): ByteArray = when (this) {
    is OutgoingContent.NoContent -> ByteArray(0)
    is OutgoingContent.ByteArrayContent -> bytes()
    is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readByteArray()
    is OutgoingContent.WriteChannelContent -> coroutineScope {
        val channel = ByteChannel()
        launch {
            writeTo(channel)
            channel.flushAndClose()
        }
        channel.readRemaining().readByteArray()
    }
    is OutgoingContent.ContentWrapper -> delegate().bytes()
    is OutgoingContent.ProtocolUpgrade -> throw IOException("Protocol upgrades aren't supported")
}
