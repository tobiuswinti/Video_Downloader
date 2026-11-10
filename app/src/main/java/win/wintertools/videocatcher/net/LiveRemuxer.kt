package win.wintertools.videocatcher.net

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import win.wintertools.videocatcher.util.PublicStorage
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Zieldatei fuer die MP4-Ausgabe: direkt im oeffentlichen Speicher oder im App-Ordner. */
class MuxOutput private constructor(
    private val context: Context,
    val name: String,
    val uri: Uri?,
    private val pfd: ParcelFileDescriptor?,
    val file: File?
) {
    fun newMuxer(): MediaMuxer =
        if (pfd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(requireNotNull(file).absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

    private var finalSize = -1L

    fun size(): Long = if (finalSize >= 0) finalSize else (pfd?.statSize ?: file?.length() ?: 0L)

    /** Datei freigeben: ab jetzt in der Galerie sichtbar. */
    fun commit(): Boolean {
        finalSize = runCatching { pfd?.statSize ?: file?.length() ?: 0L }.getOrDefault(0L)
        runCatching { pfd?.close() }
        return if (uri != null) PublicStorage.publish(context, uri) else file?.exists() == true
    }

    fun discard() {
        runCatching { pfd?.close() }
        uri?.let { PublicStorage.delete(context, it.toString()) }
        runCatching { file?.delete() }
    }

    companion object {
        fun create(context: Context, displayName: String, relPath: String, workDir: File): MuxOutput? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = PublicStorage.createPending(context, displayName, relPath)
                if (uri != null) {
                    val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
                    if (pfd != null) {
                        val real = PublicStorage.nameAndSize(context, uri).first ?: displayName
                        return MuxOutput(context, real, uri, pfd, null)
                    }
                    PublicStorage.delete(context, uri.toString())
                }
            }
            return runCatching {
                workDir.mkdirs()
                val f = File(workDir, displayName)
                f.delete()
                MuxOutput(context, f.name, null, null, f)
            }.getOrNull()
        }
    }
}

/**
 * Packt einen HLS-Stream schon WAEHREND des Ladens in eine MP4-Datei um.
 *
 * Ein eigener Faden liest die wachsende .ts-Datei als EINEN durchgehenden Strom
 * (er wartet, bis das naechste Stueck geladen ist) und schreibt Bild und Ton
 * fortlaufend in die Zieldatei. So bleiben die Zeitangaben ueber alle Segmente
 * hinweg stimmig - genau wie bei einer Umwandlung der fertigen Datei.
 *
 * Am Ende wird geprueft, ob die Laenge der MP4 zur Laenge des Streams passt.
 * Wenn nicht, meldet [finish] null; dann greift die Umwandlung nach dem
 * Download ([Remuxer]) oder die .ts-Datei bleibt erhalten.
 */
class LiveRemuxer(private val ts: File, private val open: () -> MuxOutput?) {

    class Result(val name: String, val size: Long, val uri: Uri?, val file: File?)

    private val lock = Object()
    private var available = 0L
    private var finished = false
    private var closed = false
    private var thread: Thread? = null

    @Volatile
    private var failed = false

    @Volatile
    private var completed = false

    private var out: MuxOutput? = null
    private var muxer: MediaMuxer? = null
    private var hasAudio = false
    private var videoMax = -1L
    private var audioMax = -1L
    private var samples = 0L

    /** Liefert dem Leser Daten aus der .ts-Datei und wartet auf noch nicht geladene Teile. */
    private inner class Source : MediaDataSource() {
        private var raf: RandomAccessFile? = null

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size <= 0) return 0
            val n: Int
            synchronized(lock) {
                while (!closed && !finished && position >= available) lock.wait(500)
                if (closed) throw IOException("abgebrochen")
                if (position >= available) return -1 // Ende des Streams
                n = minOf(size.toLong(), available - position).toInt()
            }
            val f = raf ?: RandomAccessFile(ts, "r").also { raf = it }
            f.seek(position)
            return f.read(buffer, offset, n)
        }

        override fun getSize(): Long = -1L

        override fun close() {
            runCatching { raf?.close() }
            raf = null
        }
    }

    /** Meldet, dass die .ts-Datei jetzt bis [bytes] vollstaendig ist (nach jedem Segment). */
    fun advance(bytes: Long) {
        synchronized(lock) {
            if (closed || failed) return
            available = bytes
            lock.notifyAll()
            if (thread == null && bytes > 0) {
                thread = Thread({ work() }, "VideoCatcher-mp4").also { it.start() }
            }
        }
    }

    private fun work() {
        val source = Source()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)

            // Je eine Bild- und Tonspur uebernehmen
            val o = open() ?: throw IllegalStateException("keine Zieldatei")
            out = o
            val m = o.newMuxer()
            muxer = m
            val trackMap = HashMap<Int, Int>()
            var videoTrack = -1
            var audioTrack = -1
            var aac = false
            var bufferSize = 2 * 1024 * 1024
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                if (isVideo && videoTrack < 0) videoTrack = i
                else if (isAudio && audioTrack < 0) {
                    audioTrack = i
                    aac = mime == "audio/mp4a-latm"
                } else continue
                extractor.selectTrack(i)
                trackMap[i] = m.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (trackMap.isEmpty()) throw IllegalStateException("keine Spuren")
            hasAudio = audioTrack >= 0
            m.start()

            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val index = extractor.sampleTrackIndex
                val track = trackMap[index]
                if (track != null) {
                    val t = extractor.sampleTime.coerceAtLeast(0)
                    if (index == audioTrack) {
                        // AAC: ADTS-Koepfe entfernen, jeden Rahmen einzeln schreiben
                        val frames = if (aac) Adts.frames(buffer, size) else null
                        if (frames != null) {
                            val step = Adts.frameDurationUs(buffer)
                            var ft = t
                            for (f in frames) {
                                // Ton muss streng aufsteigend sein
                                if (ft > audioMax) {
                                    info.set(f.offset, f.length, ft, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                                    m.writeSampleData(track, buffer, info)
                                    audioMax = ft
                                    samples++
                                }
                                ft += step
                            }
                        } else if (t > audioMax) {
                            info.set(0, size, t, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            m.writeSampleData(track, buffer, info)
                            audioMax = t
                            samples++
                        }
                    } else {
                        val flags =
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME
                            else 0
                        info.set(0, size, t, flags)
                        m.writeSampleData(track, buffer, info)
                        if (t > videoMax) videoMax = t
                        samples++
                    }
                }
                if (!extractor.advance()) break
            }
            if (samples == 0L) throw IllegalStateException("leer")
            m.stop()
            completed = true
        } catch (_: Throwable) {
            failed = true
        } finally {
            runCatching { extractor.release() }
            runCatching { source.close() }
        }
    }

    /**
     * Wartet, bis der Rest uebernommen ist, und schliesst die MP4-Datei ab.
     * @param expectedUs Laenge des Streams laut Playlist (0 = unbekannt)
     * @return die fertige Datei oder null, wenn das Ergebnis nicht stimmt
     */
    fun finish(expectedUs: Long): Result? {
        val t: Thread?
        synchronized(lock) {
            finished = true
            lock.notifyAll()
            t = thread
        }
        t?.join(20 * 60 * 1000L)
        val o = out
        if (t == null || t.isAlive || failed || !completed || o == null) {
            abandon()
            return null
        }
        runCatching { muxer?.release() }
        muxer = null

        val length = maxOf(videoMax, audioMax)
        val ok = lengthMatches(length, expectedUs) &&
            // Der Ton muss etwa so lang sein wie das Bild
            (!hasAudio || videoMax < 0 || lengthMatches(audioMax, videoMax))
        if (!ok || !o.commit()) {
            o.discard()
            out = null
            return null
        }
        out = null
        return Result(o.name, o.size(), o.uri, o.file)
    }

    /** Verwirft die angefangene Ausgabe (Pause, Abbruch, Fehler). */
    fun abandon() {
        val t: Thread?
        synchronized(lock) {
            closed = true
            failed = true
            lock.notifyAll()
            t = thread
        }
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(5000) }
        runCatching { muxer?.release() }
        muxer = null
        out?.discard()
        out = null
    }

    companion object {
        /** Stimmt eine Laenge mit der erwarteten ueberein? Erlaubt: 5 Sekunden oder 3 Prozent. */
        fun lengthMatches(actualUs: Long, expectedUs: Long): Boolean {
            if (expectedUs <= 0) return actualUs > 0
            val tolerance = maxOf(5_000_000L, expectedUs * 3 / 100)
            return kotlin.math.abs(actualUs - expectedUs) <= tolerance
        }
    }
}
