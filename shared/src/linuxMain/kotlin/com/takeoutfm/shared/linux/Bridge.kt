@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.takeoutfm.shared.linux

import com.takeoutfm.shared.api.HttpException
import com.takeoutfm.shared.store.SecretStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.mkdir

// The C API of libtakeout.so, the Linux app's only way into the shared core (linux/src/takeout.h
// declares it; Kotlin/Native's generated header types every pointer void*). Strings are UTF-8
// JSON; those handed to a callback are only valid during the call.

/** `void (*)(void* ctx, const char* json)`. */
typealias TakeoutValueCallback = CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>

/** `void (*)(void* ctx, const char* resultJson, const char* errorJson)`: one of them is null. */
typealias TakeoutResultCallback =
    CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?) -> Unit>>

/** Why a call failed: [kind] is http (with [status]), network, argument, unknown, cancelled or failed. */
@Serializable
data class CallError(val kind: String, val message: String = "", val status: Int = 0)

internal object Bridge {
    var calls: LinuxCalls? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** One at a time, in the order they were made: the player's reports (see [LinuxCalls.isOrdered]). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val inOrder = Dispatchers.Default.limitedParallelism(1)
}

/**
 * Creates the core once (later calls do nothing): [dataDir] for the database and saved state
 * (created if missing), [userAgent] for requests, [secrets] the JSON the app keeps in the
 * Secret Service (null when there's none yet), and [saveSecrets] called with the new JSON
 * after every change. Returns 0, or -1 if the core couldn't be created.
 */
@CName("takeout_init")
fun takeoutInit(
    dataDir: CPointer<ByteVar>?,
    userAgent: CPointer<ByteVar>?,
    secrets: CPointer<ByteVar>?,
    saveSecrets: TakeoutValueCallback?,
    ctx: COpaquePointer?,
): Int {
    if (Bridge.calls != null) return 0
    return try {
        val directory = dataDir?.toKString() ?: return -1
        makeDirectories(directory)
        val store = SecretStore(secrets?.toKString()) { json ->
            if (saveSecrets != null) memScoped { saveSecrets(ctx, json.cstr.ptr) }
        }
        Bridge.calls = LinuxCalls(LinuxCore(store, userAgent?.toKString() ?: "TakeoutFM", directory))
        0
    } catch (e: Exception) {
        -1
    }
}

/**
 * Runs call [name] with [argsJson] (an object; null for none) on a background thread, then
 * calls [callback] once, on that thread, with the result's JSON or a [CallError]'s. [ctx] must
 * stay valid until then. Calls made from one thread that [LinuxCalls.isOrdered] names run in
 * the order they were made.
 */
@CName("takeout_call")
fun takeoutCall(
    name: CPointer<ByteVar>?,
    argsJson: CPointer<ByteVar>?,
    callback: TakeoutResultCallback?,
    ctx: COpaquePointer?,
) {
    val call = name?.toKString().orEmpty()
    val args = argsJson?.toKString()
    val context = if (LinuxCalls.isOrdered(call)) Bridge.inOrder else Dispatchers.Default
    Bridge.scope.launch(context) {
        val (result, error) = try {
            val calls = Bridge.calls ?: throw IllegalStateException("takeout_init wasn't called")
            val parsed = args?.let { bridgeJson.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
            val value = calls.invoke(call, parsed)
            if (value == null) null to CallError("unknown", call) else value.toString() to null
        } catch (e: CancellationException) {
            null to CallError("cancelled")
        } catch (e: HttpException) {
            null to CallError("http", e.message.orEmpty(), e.code)
        } catch (e: IOException) {
            null to CallError("network", e.message.orEmpty())
        } catch (e: SerializationException) {
            null to CallError("argument", e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            null to CallError("argument", e.message.orEmpty())
        } catch (e: Throwable) {
            null to CallError("failed", e.message ?: e::class.simpleName.orEmpty())
        }
        if (callback == null) return@launch
        memScoped {
            callback(ctx, result?.cstr?.ptr, error?.let { bridgeJson.encodeToString(CallError.serializer(), it) }?.cstr?.ptr)
        }
    }
}

/**
 * Calls [callback] (on a background thread) with the JSON of shared state [name] — session,
 * library, favoriteTracks, recentlyPlayed, listenBrainz, settings — now and whenever it
 * changes. Returns a handle for [takeoutUnwatch], or null for an unknown name.
 */
@CName("takeout_watch")
fun takeoutWatch(name: CPointer<ByteVar>?, callback: TakeoutValueCallback?, ctx: COpaquePointer?): COpaquePointer? {
    val flow = Bridge.calls?.watchable(name?.toKString().orEmpty()) ?: return null
    val job = Bridge.scope.launch {
        flow.collect { value -> if (callback != null) memScoped { callback(ctx, value.toString().cstr.ptr) } }
    }
    return StableRef.create(job).asCPointer()
}

/** Stops a watch; once this returns its callback won't be called again. Not from inside it. */
@CName("takeout_unwatch")
fun takeoutUnwatch(handle: COpaquePointer?) {
    val ref = handle?.asStableRef<Job>() ?: return
    runBlocking { ref.get().cancelAndJoin() }
    ref.dispose()
}

private fun makeDirectories(path: String) {
    var at = if (path.startsWith("/")) "" else "."
    for (part in path.split('/').filter { it.isNotEmpty() }) {
        at += "/$part"
        if (mkdir(at, 0b111_000_000u) != 0 && errno != EEXIST) throw IOException("can't create $at")
    }
}
