package slussen.provider.simplefin

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestTimeout
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume

/** What one HTTP request came to: an answer with its status and body, or a closed failure kind (ADR-001 §4.1). */
internal sealed interface HttpAnswer {
    data class Answered(val status: Int, val body: String) : HttpAnswer {
        override fun toString(): String = "Answered(status=$status)"
    }

    data class Failed(val kind: FailureKind) : HttpAnswer
}

/**
 * Sends [request] with the request timeout as OkHttp's call timeout and cancels the call when the calling coroutine is
 * cancelled (ADR-001 §4.0). A timeout is [FailureKind.Timeout]; any other failure, also one that is not an I/O failure
 * such as a device that refuses network access, is [FailureKind.ConnectionLost] (S-36, NFR-004); no error text leaves
 * this function.
 */
internal suspend fun OkHttpClient.send(request: Request, timeout: RequestTimeout): HttpAnswer {
    val call = newBuilder().callTimeout(timeout.duration)
        // No redirect is followed (OkHttp's 3xx follow-up), which would send the request where the gate never admitted it.
        .followRedirects(false)
        .followSslRedirects(false)
        // No request is sent again after a failed connection, nor after a 408 (OkHttp's retry on connection failure).
        .retryOnConnectionFailure(false)
        // No request is sent again with other credentials after a 401 or a 407 (OkHttp's authenticators).
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        // No status reaches OkHttp's follow-up code at all (503 with Retry-After: 0, 421 and the rest): see [NoFollowUp].
        .apply { interceptors().add(0, ExceptionsAsIoFailure); networkInterceptors().add(NoFollowUp) }.build()
        .newCall(request)
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val kind = if (e is InterruptedIOException) FailureKind.Timeout else FailureKind.ConnectionLost
                    if (continuation.isActive) continuation.resume(HttpAnswer.Failed(kind))
                }

                override fun onResponse(call: Call, response: Response) {
                    val answer = try {
                        response.use { HttpAnswer.Answered(statusOf(it), it.body?.string().orEmpty()) }
                    } catch (e: InterruptedIOException) {
                        HttpAnswer.Failed(FailureKind.Timeout)
                    } catch (e: IOException) {
                        HttpAnswer.Failed(FailureKind.ConnectionLost)
                    } catch (e: Exception) {
                        // ADR-001 §4.0 (S-36): an exception here would end OkHttp's dispatcher thread, and the app.
                        HttpAnswer.Failed(FailureKind.ConnectionLost)
                    }
                    if (continuation.isActive) continuation.resume(answer)
                }
            },
        )
    }
}

/**
 * The first interceptor of every request (ADR-001 §4.0, S-36, NFR-004): an exception of the request that is not an I/O
 * failure, such as the `SecurityException` of an address lookup the device refuses, becomes an I/O failure inside the
 * call. OkHttp then hands it to [Callback.onFailure] as a lost connection and does not rethrow it on its dispatcher
 * thread, which would end the app. The new failure carries no text and no cause of the original (NFR-012).
 */
internal object ExceptionsAsIoFailure : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("the request could not be sent")
        }
}

/**
 * A network interceptor of every request (C2 review item 1, ADR-001 §4.0): OkHttp's follow-up code
 * (`RetryAndFollowUpInterceptor.followUpRequest`) sends a request again, past the gate, on some statuses whatever the
 * settings of the client: `503` with `Retry-After: 0`, `408` (unless the retry setting is off and `Retry-After` is above
 * zero), `421` on a shared HTTP/2 connection, `401` and `407` through an authenticator, and every redirect. This
 * interceptor runs inside that code, so it hides each such status from it before it looks: the answer goes on with a
 * status the follow-up code does not act on, and the real one in [STATUS_HEADER], which [statusOf] reads back. It holds
 * for requests with and without a body, since no part of the follow-up code sees the real status.
 */
internal object NoFollowUp : Interceptor {
    /** The header that carries the real status of a hidden answer; one the server sends is removed. */
    const val STATUS_HEADER: String = "Slussen-Hidden-Status"

    /** A status OkHttp's follow-up code does not act on. */
    private const val HIDDEN: Int = 299

    /** Every status on which OkHttp's follow-up code can send a request again or elsewhere. */
    private fun followedUp(code: Int): Boolean = code in 300..399 || code == 401 || code == 407 || code == 408 || code == 421 || code == 503

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val answer = response.newBuilder().removeHeader(STATUS_HEADER)
        if (followedUp(response.code)) answer.code(HIDDEN).header(STATUS_HEADER, response.code.toString())
        return answer.build()
    }
}

/** The status the server answered, also when [NoFollowUp] hid it from OkHttp's follow-up code. */
internal fun statusOf(response: Response): Int = response.header(NoFollowUp.STATUS_HEADER)?.toIntOrNull() ?: response.code
