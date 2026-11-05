package com.takeoutfm.shared.apple

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.TakeoutLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Device-code sign-in, as on the watch: [start] asks the server for a code and reports it with
 * the link page to show and the seconds it's valid for, then checks (at the server's interval)
 * until the user approves it at <server>/link and signs in, or it expires ([onExpired]; the app
 * shows that and offers a new code). [onFailed] when the server can't be reached. Callbacks run
 * on the main thread.
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
        onCode: (code: String, link: String, expiresIn: Int) -> Unit,
        onSignedIn: () -> Unit,
        onExpired: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val endpoint = TakeoutApi.normalizeEndpoint(host)
        job?.cancel()
        job = scope.launch {
            val code = try {
                api.requestCode(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch onFailed()
            }
            onCode(code.code, code.link, code.expiresIn)
            val tokens = api.awaitCode(code) ?: return@launch onExpired()
            session.signIn(endpoint, tokens)
            runCatching { library.refresh() }
            onSignedIn()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
