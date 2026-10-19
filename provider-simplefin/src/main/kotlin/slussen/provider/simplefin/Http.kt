package slussen.provider.simplefin

import kotlinx.coroutines.suspendCancellableCoroutine
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
    val call = newBuilder().callTimeout(timeout.duration).apply { interceptors().add(0, ExceptionsAsIoFailure) }.build()
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
                        response.use { HttpAnswer.Answered(it.code, it.body?.string().orEmpty()) }
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
