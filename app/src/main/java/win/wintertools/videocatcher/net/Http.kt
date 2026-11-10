package win.wintertools.videocatcher.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Fehler, bei dem ein erneuter Versuch nichts bringt (z. B. 403, 404, falscher Inhalt). */
class FatalDownloadException(message: String) : IOException(message)

object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun request(
        url: String,
        referer: String?,
        userAgent: String?,
        cookie: String?,
        range: String? = null,
        withOrigin: Boolean = false
    ): Request {
        val b = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw FatalDownloadException("Ungültige Adresse")
        }
        b.header("Accept", "*/*")
        // Ohne Komprimierung: sonst stimmen Dateigroesse und Fortsetzen nicht
        b.header("Accept-Encoding", "identity")
        userAgent?.takeIf { it.isNotBlank() }?.let { b.header("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { ref ->
            b.header("Referer", ref)
            if (withOrigin) originOf(ref)?.let { b.header("Origin", it) }
        }
        cookie?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        range?.let { b.header("Range", it) }
        return b.build()
    }

    private fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    }.getOrNull()

    /**
     * Fuehrt den Request aus und bricht die Verbindung sofort ab, wenn die
     * Coroutine abgebrochen wird (Pause/Loeschen) - auch mitten im Lesen.
     */
    suspend fun <T> call(req: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(req)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { block(it) }
        } catch (e: IOException) {
            // "Canceled" durch Pause nicht als Fehler melden
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    fun absolute(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref.trim())?.toString() ?: ref.trim()
}
