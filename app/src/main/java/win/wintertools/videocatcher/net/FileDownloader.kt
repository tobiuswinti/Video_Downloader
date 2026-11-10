package win.wintertools.videocatcher.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.DownloadStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Laedt eine direkte Datei. Setzt ueber HTTP-Range dort fort, wo die
 * Arbeitsdatei aufhoert, und versucht es bei Verbindungsabbruch von selbst erneut.
 */
object FileDownloader {

    private const val MAX_RETRIES = 6

    suspend fun run(d: Download) {
        val file = File(d.path)
        file.parentFile?.mkdirs()
        var failures = 0
        while (true) {
            val before = if (file.exists()) file.length() else 0L
            try {
                attempt(d, file)
                return
            } catch (e: FatalDownloadException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                val after = if (file.exists()) file.length() else 0L
                // Wenn es vorwaerts ging, zaehlt der Abbruch nicht als Fehlversuch
                failures = if (after > before) 1 else failures + 1
                if (failures > MAX_RETRIES) throw e
                DownloadStore.update(d.id) { it.copy(speed = 0, note = "Verbindung unterbrochen – neuer Versuch…") }
                delay(1500L * failures)
            }
        }
    }

    private suspend fun attempt(d: Download, file: File) {
        var existing = if (file.exists()) file.length() else 0L
        val req = Http.request(
            d.url, d.referer, d.userAgent, d.cookie,
            range = if (existing > 0) "bytes=$existing-" else null
        )

        Http.call(req) { resp ->
            if (resp.code == 416 && existing > 0) {
                // Bereich nicht erfuellbar: die Datei ist bereits vollstaendig
                DownloadStore.update(d.id) { it.copy(done = existing, total = existing, speed = 0, note = null) }
                return@call
            }
            if (!resp.isSuccessful) {
                val msg = when (resp.code) {
                    401, 403 -> "Zugriff verweigert (HTTP ${resp.code}). Der Link ist vermutlich abgelaufen – Seite neu öffnen und Video erneut wählen."
                    404, 410 -> "Datei nicht mehr vorhanden (HTTP ${resp.code})."
                    else -> "Server antwortet mit HTTP ${resp.code}"
                }
                if (resp.code in 400..499 && resp.code != 408 && resp.code != 429) throw FatalDownloadException(msg)
                throw IOException(msg)
            }

            val body = resp.body ?: throw IOException("Leere Antwort")
            val type = resp.header("Content-Type").orEmpty().lowercase()
            if (type.startsWith("text/html")) {
                throw FatalDownloadException("Der Server liefert eine Webseite statt eines Videos. Der Link ist abgelaufen oder braucht eine Anmeldung.")
            }

            val length = body.contentLength()
            val total: Long
            if (existing > 0 && resp.code == 206) {
                total = totalFromContentRange(resp.header("Content-Range"))
                    ?: if (length > 0) existing + length else -1L
            } else {
                // Server kann nicht fortsetzen: von vorne
                existing = 0L
                total = if (length > 0) length else -1L
            }

            var done = existing
            var tickTime = System.currentTimeMillis()
            var tickBytes = done
            DownloadStore.update(d.id) { it.copy(done = done, total = total, note = null) }

            FileOutputStream(file, existing > 0).use { out ->
                val input = body.byteStream()
                val buf = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n

                    val now = System.currentTimeMillis()
                    if (now - tickTime >= 500) {
                        val speed = (done - tickBytes) * 1000 / (now - tickTime)
                        val d2 = done
                        DownloadStore.update(d.id) { it.copy(done = d2, total = total, speed = speed) }
                        tickTime = now
                        tickBytes = done
                    }
                }
                out.flush()
            }

            if (total > 0 && done < total) throw IOException("Verbindung vorzeitig beendet")
            val finalDone = done
            DownloadStore.update(d.id) { it.copy(done = finalDone, total = finalDone, speed = 0) }
        }
    }

    /** "bytes 100-999/1000" -> 1000 */
    private fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}
