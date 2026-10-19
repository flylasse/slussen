package slussen.provider.enablebanking

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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
 * cancelled (ADR-001 §4.0). A timeout is [FailureKind.Timeout], any other I/O failure [FailureKind.ConnectionLost];
 * no error text leaves this function.
 */
internal suspend fun OkHttpClient.send(request: Request, timeout: RequestTimeout): HttpAnswer {
    val call = newBuilder().callTimeout(timeout.duration).build().newCall(request)
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
                    }
                    if (continuation.isActive) continuation.resume(answer)
                }
            },
        )
    }
}
