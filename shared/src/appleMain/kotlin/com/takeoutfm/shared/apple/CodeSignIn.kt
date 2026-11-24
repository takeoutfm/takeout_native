package com.takeoutfm.shared.apple

import com.takeoutfm.shared.api.HttpException
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.TakeoutLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * Device-code sign-in, as on the watch: [start] asks the server for a code and reports it with
 * the link page to show and the seconds it's valid for, then checks (at the server's interval)
 * until the user approves it at <server>/link and signs in, or it expires ([onExpired]; the app
 * shows that and offers a new code). [onFailed] when there's no code (SIGN-5): `answered` true
 * when the server answered but not with one (an error, or not a Takeout server), false when it
 * can't be reached. Callbacks run on the main thread.
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
        onFailed: (answered: Boolean) -> Unit,
    ) {
        val endpoint = TakeoutApi.normalizeEndpoint(host)
        job?.cancel()
        job = scope.launch {
            val code = try {
                api.requestCode(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // as Linux tells them apart: an HTTP error or an answer that isn't a code
                return@launch onFailed(e is HttpException || e is SerializationException)
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
