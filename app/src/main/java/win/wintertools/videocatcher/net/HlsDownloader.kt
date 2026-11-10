package win.wintertools.videocatcher.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.DownloadStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Laedt einen HLS-Stream: Playlist lesen, Segmente der Reihe nach holen und
 * aneinanderhaengen. Fortsetzen geht ueber die Nummer des letzten fertigen Segments.
 *
 * Verschluesselte Streams und Livestreams werden bewusst nicht verarbeitet.
 */
object HlsDownloader {

    class Playlist(
        val segments: List<String>,
        val initSegment: String?,
        val separateAudio: Boolean,
        /** Laenge laut Playlist (Summe der Segmentdauern) in Millisekunden */
        val durationMs: Long
    ) {
        /** fMP4-Segmente ergeben aneinandergehaengt direkt eine MP4-Datei */
        val isFmp4: Boolean
            get() = initSegment != null ||
                segments.firstOrNull()?.substringBefore('?')?.lowercase()
                    ?.let { it.endsWith(".m4s") || it.endsWith(".mp4") || it.endsWith(".m4v") } == true
    }

    /**
     * @param live wenn gesetzt, wird ein .ts-Stream schon waehrend des Ladens nach MP4 umgepackt
     * @return die geladene Datei (.ts oder bei fMP4-Streams direkt .mp4)
     */
    suspend fun run(d: Download, live: LiveRemuxer? = null): File {
        val pl = load(d.url, d)
        var file = File(d.path)
        file.parentFile?.mkdirs()

        // Fortsetzen nur, wenn die Playlist noch dieselbe ist
        val resume = d.segDone > 0 && d.segTotal == pl.segments.size &&
            file.exists() && file.length() >= d.done && d.done > 0
        val start = if (resume) d.segDone else 0

        if (resume) {
            // Angefangenes Segment abschneiden
            RandomAccessFile(file, "rw").use { it.setLength(d.done) }
        } else {
            file.delete()
            if (pl.isFmp4 && file.name.endsWith(".ts")) {
                file = File(file.parentFile, file.name.removeSuffix(".ts") + ".mp4")
                file.delete()
                val f = file
                DownloadStore.update(d.id, force = true) { it.copy(path = f.absolutePath, fileName = f.name) }
            }
        }

        var bytes = if (resume) d.done else 0L
        val total = pl.segments.size
        val note = if (pl.separateAudio) "Ton liegt in einer getrennten Spur – die Datei kann ohne Ton sein." else null

        // fMP4-Streams sind schon MP4: nichts umzupacken
        val remux = if (pl.isFmp4) null else live
        // Beim Fortsetzen: der schon geladene Teil wird im Hintergrund nachgeholt
        remux?.advance(bytes)

        DownloadStore.update(d.id, force = true) {
            it.copy(
                segTotal = total, segDone = start, done = bytes, total = -1L, note = note,
                durationMs = if (pl.durationMs > 0) pl.durationMs else it.durationMs
            )
        }

        if (start == 0 && pl.initSegment != null) {
            bytes += fetchInto(pl.initSegment, file, d)
        }

        var tickTime = System.currentTimeMillis()
        var tickBytes = bytes
        for (i in start until total) {
            currentCoroutineContext().ensureActive()
            bytes += fetchInto(pl.segments[i], file, d)
            // Das Segment ist vollstaendig: fuer die MP4-Datei freigeben
            remux?.advance(bytes)

            val now = System.currentTimeMillis()
            val speed = (bytes - tickBytes) * 1000 / (now - tickTime).coerceAtLeast(1)
            tickTime = now
            tickBytes = bytes
            val b = bytes
            // Nach jedem fertigen Segment sichern: das ist der Wiedereinstiegspunkt
            DownloadStore.update(d.id, force = (i % 5 == 4)) {
                it.copy(segDone = i + 1, done = b, speed = speed)
            }
        }
        val b = bytes
        DownloadStore.update(d.id, force = true) { it.copy(segDone = total, done = b, speed = 0) }
        return file
    }

    /** Haengt ein Segment an die Datei an; bei Fehler wird der Teil wieder abgeschnitten. */
    private suspend fun fetchInto(url: String, file: File, d: Download): Long {
        val startLength = if (file.exists()) file.length() else 0L
        var failures = 0
        while (true) {
            try {
                var written = 0L
                Http.call(Http.request(url, d.referer, d.userAgent, d.cookie, withOrigin = true)) { resp ->
                    if (!resp.isSuccessful) {
                        val msg = "Segment nicht ladbar (HTTP ${resp.code})"
                        if (resp.code == 403 || resp.code == 404 || resp.code == 410) {
                            throw FatalDownloadException("$msg. Der Stream-Link ist vermutlich abgelaufen – Seite neu öffnen.")
                        }
                        throw IOException(msg)
                    }
                    val body = resp.body ?: throw IOException("Segment leer")
                    FileOutputStream(file, true).use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                        }
                        out.flush()
                    }
                }
                return written
            } catch (e: FatalDownloadException) {
                truncate(file, startLength)
                throw e
            } catch (e: IOException) {
                truncate(file, startLength)
                currentCoroutineContext().ensureActive()
                failures++
                if (failures > 4) throw e
                delay(1000L * failures)
            }
        }
    }

    private fun truncate(file: File, length: Long) {
        runCatching {
            if (file.exists() && file.length() > length) {
                RandomAccessFile(file, "rw").use { it.setLength(length) }
            }
        }
    }

    suspend fun fetchText(url: String, d: Download): String =
        fetchText(url, d.referer, d.userAgent, d.cookie)

    suspend fun fetchText(url: String, referer: String?, ua: String?, cookie: String?): String =
        Http.call(Http.request(url, referer, ua, cookie, withOrigin = true)) { resp ->
            if (!resp.isSuccessful) {
                val msg = "Playlist nicht ladbar (HTTP ${resp.code})"
                if (resp.code in 400..499) throw FatalDownloadException("$msg. Der Link ist vermutlich abgelaufen – Seite neu öffnen.")
                throw IOException(msg)
            }
            resp.body?.string() ?: throw IOException("Playlist leer")
        }

    /** Liest die Playlist; bei einer Master-Playlist wird die beste Qualitaet gewaehlt. */
    private suspend fun load(url: String, d: Download): Playlist {
        var playlistUrl = url
        var text = fetchText(playlistUrl, d)
        var separateAudio = false

        if (text.contains("#EXT-X-STREAM-INF")) {
            val best = bestVariant(text) ?: throw FatalDownloadException("Keine Qualitätsstufe in der Playlist gefunden")
            separateAudio = best.audioGroup != null &&
                Regex("#EXT-X-MEDIA:[^\\n]*TYPE=AUDIO[^\\n]*URI=").containsMatchIn(text)
            playlistUrl = Http.absolute(playlistUrl, best.uri)
            text = fetchText(playlistUrl, d)
        }

        if (!text.contains("#EXTM3U")) throw FatalDownloadException("Das ist keine gültige Playlist")
        if (Regex("#EXT-X-KEY:[^\\n]*METHOD=(?!NONE)").containsMatchIn(text)) {
            throw FatalDownloadException("Der Stream ist verschlüsselt und kann nicht geladen werden.")
        }
        if (text.contains("#EXT-X-BYTERANGE")) {
            throw FatalDownloadException("Dieses Stream-Format (Byte-Bereiche) wird nicht unterstützt.")
        }
        if (!text.contains("#EXT-X-ENDLIST")) {
            throw FatalDownloadException("Das ist ein Livestream – nur fertige Videos können geladen werden.")
        }

        val init = Regex("#EXT-X-MAP:[^\\n]*URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?.let { Http.absolute(playlistUrl, it) }
        val segments = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Http.absolute(playlistUrl, it) }
        if (segments.isEmpty()) throw FatalDownloadException("Die Playlist enthält keine Segmente")
        val seconds = Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        return Playlist(segments, init, separateAudio, (seconds * 1000).toLong())
    }

    class Variant(val uri: String, val bandwidth: Long, val height: Int, val audioGroup: String?)

    fun variants(master: String): List<Variant> {
        val lines = master.lines().map { it.trim() }
        val out = ArrayList<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val bw = Regex("[:,]BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val audio = Regex("AUDIO=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            out.add(Variant(uri, bw, height, audio))
        }
        return out
    }

    private fun bestVariant(master: String): Variant? =
        variants(master).maxWithOrNull(compareBy<Variant> { it.height }.thenBy { it.bandwidth })
}
