package win.wintertools.videocatcher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import win.wintertools.videocatcher.browser.MediaSniffer
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.DownloadStore
import win.wintertools.videocatcher.data.Kind
import win.wintertools.videocatcher.data.TitleParser
import win.wintertools.videocatcher.net.FatalDownloadException
import win.wintertools.videocatcher.net.FileDownloader
import win.wintertools.videocatcher.net.HlsDownloader
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Prueft die Download-Logik gegen einen kleinen Testserver auf dem eigenen Rechner:
 * Fortsetzen nach Pause, Streams, Fehlerfaelle.
 */
class DownloadLogicTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var dir: File
    private var nextId = 1L

    private val movie = Random(1).nextBytes(3_000_000)
    private val segments = (0 until 8).map { Random(100 + it).nextBytes(40_000 + it * 1000) }
    private val init = Random(7).nextBytes(900)

    /** Verzoegerung pro Antwortblock in ms - damit man mitten im Laden pausieren kann */
    @Volatile
    private var slowMs = 0L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vc-test").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        val hls = "application/vnd.apple.mpegurl"
        Regex("/(hd|fmp4)/seg(\\d+)\\.(ts|m4s)").matchEntire(path)?.let { m ->
            return body(200, segments[m.groupValues[2].toInt()], "video/mp2t")
        }
        return when (path) {
            "/movie.mp4" -> ranged(request, movie)
            "/norange.mp4" -> body(200, movie, "video/mp4")
            "/page.mp4" -> body(200, "<html>Login</html>".toByteArray(), "text/html; charset=utf-8")
            "/gone.mp4" -> MockResponse().setResponseCode(403)
            "/master.m3u8" -> body(
                200,
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
                hd/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720
                mid/index.m3u8
                """.trimIndent().toByteArray(), hls
            )
            "/hd/index.m3u8" -> body(200, buildString {
                append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n")
                segments.indices.forEach { append("#EXTINF:10.0,\nseg$it.ts?token=abc\n") }
                append("#EXT-X-ENDLIST\n")
            }.toByteArray(), hls)
            "/low/index.m3u8" -> body(200, "#EXTM3U\n#EXTINF:1,\nwrong.ts\n#EXT-X-ENDLIST\n".toByteArray(), hls)
            "/fmp4/init.mp4" -> body(200, init, "video/mp4")
            "/fmp4/index.m3u8" -> body(200, buildString {
                append("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n")
                segments.indices.forEach { append("#EXTINF:4.0,\nseg$it.m4s\n") }
                append("#EXT-X-ENDLIST\n")
            }.toByteArray(), hls)
            "/crypt.m3u8" -> body(200, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n".toByteArray(), hls)
            "/live.m3u8" -> body(200, "#EXTM3U\n#EXTINF:1,\na.ts\n".toByteArray(), hls)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun body(code: Int, data: ByteArray, type: String): MockResponse {
        val r = MockResponse().setResponseCode(code).setHeader("Content-Type", type).setBody(Buffer().write(data))
        if (slowMs > 0) r.throttleBody(16_384, slowMs, TimeUnit.MILLISECONDS)
        return r
    }

    private fun ranged(request: RecordedRequest, data: ByteArray): MockResponse {
        val range = request.getHeader("Range") ?: return body(200, data, "video/mp4")
        val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
        if (start >= data.size) return MockResponse().setResponseCode(416)
        return body(206, data.copyOfRange(start, data.size), "video/mp4")
            .setHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
    }

    private fun newDownload(path: String, kind: Kind, name: String): Download {
        val d = Download(
            id = nextId++, url = base + path, title = name, fileName = name,
            path = File(dir, name).absolutePath, kind = kind
        )
        DownloadStore.add(d)
        return d
    }

    // ------------------------------------------------------------------ Dateien

    @Test
    fun fileDownloadsCompletely() = runBlocking {
        val d = newDownload("/movie.mp4", Kind.FILE, "a.mp4")
        FileDownloader.run(d)
        assertArrayEquals(movie, File(d.path).readBytes())
        assertEquals(movie.size.toLong(), DownloadStore.get(d.id)!!.total)
    }

    @Test
    fun fileResumesAfterPauseWithoutStartingOver() = runBlocking {
        val d = newDownload("/movie.mp4", Kind.FILE, "b.mp4")
        slowMs = 4
        val job = CoroutineScope(Dispatchers.IO).launch { FileDownloader.run(d) }
        while ((File(d.path).takeIf { it.exists() }?.length() ?: 0) < 400_000) delay(10)
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()
        // Pause muss sofort greifen, nicht erst nach einem Lese-Timeout
        assertTrue("Pause dauerte zu lange", System.currentTimeMillis() - t0 < 2000)

        val partial = File(d.path).length()
        assertTrue(partial in 400_000 until movie.size)
        slowMs = 0
        FileDownloader.run(DownloadStore.get(d.id)!!)
        assertArrayEquals(movie, File(d.path).readBytes())
    }

    @Test
    fun fileRestartsWhenServerIgnoresRange() = runBlocking {
        val d = newDownload("/norange.mp4", Kind.FILE, "c.mp4")
        File(d.path).writeBytes(ByteArray(5000) { 1 })
        FileDownloader.run(d)
        assertArrayEquals(movie, File(d.path).readBytes())
    }

    @Test
    fun alreadyCompleteFileIsAccepted() = runBlocking {
        val d = newDownload("/movie.mp4", Kind.FILE, "d.mp4")
        File(d.path).writeBytes(movie)
        FileDownloader.run(d)
        assertEquals(movie.size.toLong(), File(d.path).length())
    }

    @Test
    fun webPageInsteadOfVideoIsRejected() = runBlocking {
        val d = newDownload("/page.mp4", Kind.FILE, "e.mp4")
        try {
            FileDownloader.run(d)
            fail("haette scheitern muessen")
        } catch (e: FatalDownloadException) {
            assertTrue(e.message!!.contains("Webseite"))
        }
    }

    @Test
    fun forbiddenFailsAtOnceWithoutRetries() = runBlocking {
        val d = newDownload("/gone.mp4", Kind.FILE, "f.mp4")
        val t0 = System.currentTimeMillis()
        try {
            FileDownloader.run(d)
            fail("haette scheitern muessen")
        } catch (e: FatalDownloadException) {
            assertTrue(System.currentTimeMillis() - t0 < 1500)
        }
    }

    // ------------------------------------------------------------------ Streams

    private val allSegments: ByteArray get() = segments.reduce { a, b -> a + b }

    @Test
    fun hlsPicksBestQualityAndJoinsSegmentsInOrder() = runBlocking {
        val d = newDownload("/master.m3u8", Kind.HLS, "s.ts")
        val file = HlsDownloader.run(d)
        assertArrayEquals(allSegments, file.readBytes())
        val after = DownloadStore.get(d.id)!!
        assertEquals(segments.size, after.segDone)
        assertEquals(segments.size, after.segTotal)
    }

    @Test
    fun hlsResumesAtLastFinishedSegment() = runBlocking {
        val d = newDownload("/hd/index.m3u8", Kind.HLS, "r.ts")
        slowMs = 15
        val job = CoroutineScope(Dispatchers.IO).launch { HlsDownloader.run(d) }
        while ((DownloadStore.get(d.id)?.segDone ?: 0) < 3) delay(10)
        delay(40) // mitten in einem Segment abbrechen
        job.cancelAndJoin()

        val paused = DownloadStore.get(d.id)!!
        assertTrue(paused.segDone in 3 until segments.size)
        slowMs = 0
        val file = HlsDownloader.run(paused)
        assertArrayEquals(allSegments, file.readBytes())
    }

    @Test
    fun fmp4StreamBecomesMp4WithInitSegmentFirst() = runBlocking {
        val d = newDownload("/fmp4/index.m3u8", Kind.HLS, "m.ts")
        val file = HlsDownloader.run(d)
        assertEquals("m.mp4", file.name)
        assertArrayEquals(init + allSegments, file.readBytes())
        assertEquals("m.mp4", DownloadStore.get(d.id)!!.fileName)
    }

    @Test
    fun encryptedAndLiveStreamsAreRefusedWithClearMessage() = runBlocking {
        for ((path, word) in listOf("/crypt.m3u8" to "verschlüsselt", "/live.m3u8" to "Livestream")) {
            val d = newDownload(path, Kind.HLS, "x${nextId}.ts")
            try {
                HlsDownloader.run(d)
                fail("haette scheitern muessen: $path")
            } catch (e: FatalDownloadException) {
                assertTrue(e.message!!.contains(word))
            }
        }
    }

    // --------------------------------------------------------------- Erkennung

    @Test
    fun snifferRecognisesVideosAndIgnoresTheRest() {
        fun kind(url: String, headers: Map<String, String>? = null) = MediaSniffer.classify(url, headers)?.kind

        assertEquals(Kind.FILE, kind("https://cdn.example.com/v/clip.mp4?token=1"))
        assertEquals(Kind.FILE, kind("https://cdn.example.com/v/clip.WEBM"))
        assertEquals(Kind.HLS, kind("https://cdn.example.com/hls/master.m3u8?x=1"))
        // Einzelteile eines Streams und sonstige Dateien gehoeren nicht in die Liste
        assertNull(kind("https://cdn.example.com/hls/seg-12.ts"))
        assertNull(kind("https://cdn.example.com/dash/chunk.m4s"))
        assertNull(kind("https://example.com/app.js"))
        assertNull(kind("https://example.com/poster.jpg"))
        assertNull(kind("https://example.com/"))
        assertNull(kind("blob:https://example.com/123"))
        // Video ohne Endung: an der Anfrage eines video-Elements erkennbar
        val mediaHeaders = mapOf("Accept-Encoding" to "identity;q=1, *;q=0", "Range" to "bytes=0-", "Referer" to "https://player.example/embed")
        val hit = MediaSniffer.classify("https://cdn.example.com/get_video?id=5", mediaHeaders)
        assertNotNull(hit)
        assertEquals("https://player.example/embed", hit!!.referer)
        assertNull(kind("https://cdn.example.com/get_video?id=5", mapOf("Accept-Encoding" to "gzip, deflate")))
        // Gleiche Datei mit anderem Zugriffs-Parameter gilt als derselbe Treffer
        assertEquals(
            MediaSniffer.classify("https://a.example/v.mp4?t=1", null)!!.key,
            MediaSniffer.classify("https://a.example/v.mp4?t=2", null)!!.key
        )
    }

    @Test
    fun describeShowsSizeAndDuration() = runBlocking {
        val file = MediaSniffer.classify("$base/movie.mp4", null)!!
        assertEquals("MP4 · 2,9 MB", MediaSniffer.describe(file, null, null, null))
        val stream = MediaSniffer.classify("$base/hd/index.m3u8", null)!!
        assertEquals("Stream · 1:20 Min.", MediaSniffer.describe(stream, null, null, null))
    }

    @Test
    fun streamOffersQualitiesWithEstimatedSize() = runBlocking {
        val master = MediaSniffer.classify("$base/master.m3u8", null)!!
        val info = MediaSniffer.inspect(master, null, null, null)
        assertEquals("Stream · 1:20 Min.", info.label)
        // Hoechste Stufe zuerst
        assertEquals(listOf("1080p", "720p", "360p"), info.qualities.map { it.label })
        assertEquals("$base/hd/index.m3u8", info.qualities[0].url)
        // Groesse = Datenrate x Laenge: 2,5 Mbit/s x 80 s
        assertEquals(25_000_000L, info.qualities[0].sizeBytes)
        assertEquals(9_000_000L, info.qualities[1].sizeBytes)

        // Eine gewaehlte Stufe laesst sich direkt laden
        val d = newDownload("/hd/index.m3u8", Kind.HLS, "q.ts")
        assertArrayEquals(allSegments, HlsDownloader.run(d).readBytes())

        // Stream mit nur einer Stufe: keine Auswahl
        val single = MediaSniffer.classify("$base/hd/index.m3u8", null)!!
        assertTrue(MediaSniffer.inspect(single, null, null, null).qualities.isEmpty())
    }

    // ------------------------------------------------------------- Einordnung

    @Test
    fun titleParserFindsSeriesSeasonAndEpisode() {
        fun p(t: String) = TitleParser.parse(t).let { Triple(it.series, it.season, it.episode) }

        assertEquals(Triple("Der Bergdoktor", 3, 7), p("Der Bergdoktor S03E07 - Stream"))
        assertEquals(Triple("Der Bergdoktor", 3, 7), p("Der.Bergdoktor.s03.e07.720p"))
        assertEquals(Triple("Tatort", 1, 12), p("Tatort - Staffel 1 Folge 12"))
        assertEquals(Triple("Tatort", 2, 4), p("Tatort Season 2, Episode 4"))
        assertEquals(Triple("Die Sendung", 4, 15), p("Die Sendung 4x15"))
        assertEquals(Triple("Die Sendung", 1, 9), p("Die Sendung – Folge 9"))
        // Filme bleiben Filme
        assertEquals(Triple(null, null, null), p("Ein ganz normaler Film (1998)"))
        assertEquals(Triple(null, null, null), p("Video 1920x1080"))
        assertEquals(Triple(null, null, null), p("S01E01"))
    }

    @Test
    fun filesAreSortedIntoFoldersByGenreAndSeason() {
        fun path(name: String, genre: String?, series: String?, season: Int?) =
            win.wintertools.videocatcher.util.PublicStorage.relativePath(name, genre, series, season)

        // android.os.Environment liefert im Test null fuer die Ordnernamen - nur das Ende pruefen
        assertTrue(path("a.mp4", null, null, null).endsWith("/VideoCatcher"))
        assertTrue(path("a.mp4", "Krimi", null, null).endsWith("/VideoCatcher/Krimi"))
        assertTrue(path("a.mp4", "Krimi", "Tatort", 2).endsWith("/VideoCatcher/Serien/Tatort/Staffel 2"))
        assertTrue(path("a.mp4", null, "A/B: C?", null).endsWith("/VideoCatcher/Serien/A B C/Staffel 1"))
    }

    @Test
    fun adtsAudioIsSplitIntoRawFrames() {
        fun frame(size: Int, crc: Boolean): ByteArray {
            val b = ByteArray(size) { 0x55 }
            b[0] = 0xFF.toByte()
            b[1] = (if (crc) 0xF0 else 0xF1).toByte()
            b[2] = 0x4C // Abtastrate 48000
            b[3] = (0x80 or ((size shr 11) and 0x03)).toByte()
            b[4] = ((size shr 3) and 0xFF).toByte()
            b[5] = (((size and 0x07) shl 5) or 0x1F).toByte()
            b[6] = 0xFC.toByte()
            return b
        }
        fun frames(bytes: ByteArray, size: Int = bytes.size) =
            win.wintertools.videocatcher.net.Adts.frames(java.nio.ByteBuffer.wrap(bytes), size)?.map { it.offset to it.length }

        // Ein Rahmen: Kopf von 7 bzw. 9 Bytes faellt weg
        assertEquals(listOf(7 to 364), frames(frame(371, crc = false)))
        assertEquals(listOf(9 to 362), frames(frame(371, crc = true)))
        // Mehrere Rahmen in einem Tonstueck werden einzeln geliefert
        assertEquals(listOf(7 to 293, 307 to 393, 707 to 193), frames(frame(300, false) + frame(400, false) + frame(200, false)))
        // Passt die Laenge nicht oder ist es rohes AAC: unveraendert lassen
        assertNull(frames(frame(371, crc = false), 300))
        assertNull(frames(frame(300, false) + ByteArray(50) { 0x21 }))
        assertNull(frames(ByteArray(200) { 0x21 }))
        // 1024 Abtastwerte bei 48 kHz
        assertEquals(21333L, win.wintertools.videocatcher.net.Adts.frameDurationUs(java.nio.ByteBuffer.wrap(frame(371, false))))
    }

    @Test
    fun convertedLengthMustMatchTheStream() {
        val ok = win.wintertools.videocatcher.net.LiveRemuxer.Companion::lengthMatches
        val minute = 60_000_000L
        assertTrue(ok(90 * minute, 90 * minute))
        assertTrue(ok(90 * minute - 4_000_000, 90 * minute))
        // Ein Film, der auf wenige Sekunden zusammengeschoben wurde, faellt durch
        assertTrue(!ok(6_000_000, 90 * minute))
        assertTrue(!ok(45 * minute, 90 * minute))
        // Laenge unbekannt: nur leere Ergebnisse ablehnen
        assertTrue(ok(minute, 0))
        assertTrue(!ok(-1, 0))
    }

    @Test
    fun playlistDurationIsStoredForTheCheck() = runBlocking {
        val d = newDownload("/hd/index.m3u8", Kind.HLS, "dur.ts")
        HlsDownloader.run(d)
        assertEquals(80_000L, DownloadStore.get(d.id)!!.durationMs)
    }
}
