package com.takeoutfm.shared.apple

import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.TakeoutLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Device-code sign-in, as on the watch: [start] asks the server for a code, reports it with the
 * link page to show, then checks every 3 s (for 10 minutes) until the user approves it at
 * <server>/link, then signs in. Callbacks run on the main thread.
 */
class CodeSignIn(
    private val api: TakeoutApi,
    private val session: TakeoutSession,
    private val library: TakeoutLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun start(
        host: String,
        onCode: (code: String, link: String) -> Unit,
        onSignedIn: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val endpoint = TakeoutApi.normalizeEndpoint(host)
        job?.cancel()
        job = scope.launch {
            try {
                val code = api.requestCode(endpoint)
                onCode(code.code, "$endpoint/link")
                repeat(200) {
                    delay(3_000)
                    val tokens: ApiTokens? = try {
                        api.checkCode(endpoint, code)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (tokens != null) {
                        session.signIn(endpoint, tokens)
                        runCatching { library.refresh() }
                        onSignedIn()
                        return@launch
                    }
                }
                onFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
