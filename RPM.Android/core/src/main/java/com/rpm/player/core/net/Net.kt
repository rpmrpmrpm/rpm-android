package com.rpm.player.core.net

import com.rpm.player.core.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The key this app sends to the RPM API. The source holds none: it comes from the build (`rpm.appKey` or
 * `RPM_APP_KEY`, see core/build.gradle.kts). A release build carries the official key, and a contributor's
 * build their own developer key from rpmrpmrpm.com/developer. The key ships inside the app and can be read
 * back out of it, so it filters casual traffic and says whose build is asking; it does not protect anything.
 * The same key is `AppKey` in the iOS app and `RpmApi` in RPM.Core. The server accepts the current and the
 * previous official key.
 */
object AppKey {
    const val HEADER = "X-RPM-Key"
    const val VALUE: String = BuildConfig.RPM_APP_KEY
}

object Hosts {
    const val ACCOUNTS = "https://accounts.rpmrpmrpm.com/"
    const val API = "https://api.rpmrpmrpm.com/"
}

/** The one JSON configuration that every wire type is read with. Unknown fields are ignored, and a null
 * where a default exists reads as the default, because rows uploaded by older desktop versions lack fields. */
val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * The client that every cloud request goes through. It allows up to [PER_HOST] requests to one host at
 * a time, where the OkHttp limit is 5. A cover that is not cached costs one request to the API for a
 * signed address and one to the store for the picture. With a limit of 5, a wall row of six new covers
 * plus the row decoded ahead queued behind each other on each host and filled in over several seconds.
 * A request to play waited in the same queue.
 */
fun httpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .dispatcher(Dispatcher().apply { maxRequestsPerHost = PER_HOST })
    .build()

/**
 * The client the image loader fetches with: [base] with the app key on every request to the API's own host. The API's
 * pictures (a person's photo and banner) are public reads, and a public read without the key is refused (401), so the
 * loader showed only the initial where a photo was. Other hosts (cover stores, logos) are not given the key.
 */
fun pictureClient(base: OkHttpClient): OkHttpClient {
    val api = Hosts.API.toHttpUrl().host
    return base.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != api || request.header(AppKey.HEADER) != null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header(AppKey.HEADER, AppKey.VALUE).build())
            }
        }
        .build()
}

/** How many requests the cloud client sends to one host at once. */
private const val PER_HOST = 16

/** Runs the call without blocking a thread, and cancels it when the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
}

/** Escapes a path segment so a name that holds a slash stays one segment. Everything outside
 * `A-Z a-z 0-9 - . _ ~` is percent-encoded from its UTF-8 bytes. */
fun escapeSegment(text: String): String = buildString {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt().toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            append(c)
        } else {
            append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}
