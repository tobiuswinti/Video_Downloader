package win.wintertools.videocatcher.net

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import win.wintertools.videocatcher.util.PublicStorage
import java.io.File
import java.io.FileInputStream
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

    fun size(): Long = pfd?.statSize ?: file?.length() ?: 0L

    /** Datei freigeben: ab jetzt in der Galerie sichtbar. */
    fun commit(): Boolean {
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
 * Packt einen HLS-Stream schon WAEHREND des Ladens in eine MP4-Datei um:
 * jedes fertig geladene Segment wird sofort in die Zieldatei geschrieben.
 * Am Ende muss nur noch das Inhaltsverzeichnis der MP4 geschrieben werden -
 * das Warten auf eine Umwandlung nach dem Download entfaellt.
 *
 * Geht dabei etwas schief, meldet [finish] null; dann wird wie frueher
 * nach dem Download umgewandelt ([Remuxer]).
 */
class LiveRemuxer(private val open: () -> MuxOutput?) {

    class Result(val name: String, val size: Long, val uri: Uri?, val file: File?)

    private var out: MuxOutput? = null
    private var muxer: MediaMuxer? = null
    private var started = false
    private var failed = false

    /** Art der Spur ("video"/"audio") -> Spur in der Zieldatei */
    private val tracks = HashMap<String, Int>()
    private var buffer = ByteBuffer.allocate(2 * 1024 * 1024)
    private val info = MediaCodec.BufferInfo()

    /** Ausgabezeit = Zeit im Stream + delta */
    private var delta = 0L
    private var outMax = 0L
    private val rawLast = HashMap<String, Long>()
    private var lastAudioOut = -1L
    private var audioIsAac = false
    private var samples = 0L
    private var fedBytes = 0L

    val active: Boolean get() = !failed

    /** Uebernimmt den Bereich [offset, offset+length) der .ts-Datei (ein oder mehrere ganze Segmente). */
    fun feed(ts: File, offset: Long, length: Long) {
        if (failed || length <= 0) return
        try {
            feedInternal(ts, offset, length)
            fedBytes += length
        } catch (_: Throwable) {
            failed = true
            releaseAll(discard = true)
        }
    }

    private fun kindsOf(extractor: MediaExtractor): HashMap<Int, String> {
        val kinds = HashMap<Int, String>()
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            val kind = when {
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                else -> continue
            }
            if (kinds.containsValue(kind)) continue
            kinds[i] = kind
        }
        return kinds
    }

    /** Erster Abschnitt: Zieldatei anlegen, Spuren anmelden, Startzeit bestimmen. */
    private fun start(ts: File, offset: Long, length: Long) {
        val extractor = MediaExtractor()
        try {
            FileInputStream(ts).use { fis ->
                extractor.setDataSource(fis.fd, offset, length)
                val kinds = kindsOf(extractor)
                if (kinds.isEmpty()) throw IllegalStateException("keine Spuren")

                val o = open() ?: throw IllegalStateException("keine Zieldatei")
                out = o
                val m = o.newMuxer()
                muxer = m
                for ((index, kind) in kinds) {
                    val format = extractor.getTrackFormat(index)
                    tracks[kind] = m.addTrack(format)
                    if (kind == "audio") audioIsAac = format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val need = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (need > buffer.capacity()) buffer = ByteBuffer.allocate(need)
                    }
                }
                m.start()

                // Kleinste Zeit im ersten Abschnitt suchen, damit das Video bei 0 beginnt
                kinds.keys.forEach { extractor.selectTrack(it) }
                var min = Long.MAX_VALUE
                while (true) {
                    val t = extractor.sampleTime
                    if (t < 0) break
                    if (t < min) min = t
                    if (!extractor.advance()) break
                }
                if (min == Long.MAX_VALUE) throw IllegalStateException("leer")
                delta = -min
                started = true
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun feedInternal(ts: File, offset: Long, length: Long) {
        if (!started) start(ts, offset, length)

        val extractor = MediaExtractor()
        try {
            FileInputStream(ts).use { fis ->
                extractor.setDataSource(fis.fd, offset, length)
                val kinds = kindsOf(extractor)
                kinds.keys.forEach { extractor.selectTrack(it) }

                val m = requireNotNull(muxer)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val kind = kinds[extractor.sampleTrackIndex]
                    val track = if (kind != null) tracks[kind] else null
                    if (kind != null && track != null) {
                        val raw = extractor.sampleTime
                        // Zeitsprung im Stream (z. B. eingeschobene Werbung): nahtlos weiterzaehlen
                        val last = rawLast[kind]
                        if (last != null && (raw < last - 2_000_000 || raw > last + 5_000_000)) {
                            delta = outMax + 100_000 - raw
                            rawLast.clear()
                        }
                        rawLast[kind] = raw
                        var t = raw + delta
                        if (t < 0) t = 0
                        // Ton muss streng aufsteigend sein; ueberlappende Stuecke weglassen
                        val write = kind != "audio" || t > lastAudioOut
                        if (write) {
                            if (kind == "audio") lastAudioOut = t
                            if (t > outMax) outMax = t
                            val flags =
                                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME
                                else 0
                            // AAC aus TS traegt einen ADTS-Kopf, der in MP4 nicht stehen darf
                            val skip = if (kind == "audio" && audioIsAac) Adts.headerLength(buffer, size) else 0
                            info.set(skip, size - skip, t, flags)
                            m.writeSampleData(track, buffer, info)
                            samples++
                        }
                    }
                    if (!extractor.advance()) break
                }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Schliesst die MP4-Datei ab. @return die fertige Datei oder null, wenn es nicht geklappt hat */
    fun finish(): Result? {
        val o = out
        if (failed || !started || o == null || samples == 0L) {
            releaseAll(discard = true)
            return null
        }
        return try {
            requireNotNull(muxer).stop()
            runCatching { muxer?.release() }
            muxer = null
            val size = o.size()
            // Plausibilitaet: eine viel kleinere Datei heisst, es fehlt etwas
            if (size < fedBytes / 2 || !o.commit()) throw IllegalStateException("unvollstaendig")
            out = null
            Result(o.name, size, o.uri, o.file)
        } catch (_: Throwable) {
            failed = true
            releaseAll(discard = true)
            null
        }
    }

    /** Verwirft die angefangene Ausgabe (Pause, Abbruch, Fehler). */
    fun abandon() {
        failed = true
        releaseAll(discard = true)
    }

    private fun releaseAll(discard: Boolean) {
        runCatching { muxer?.release() }
        muxer = null
        if (discard) out?.discard()
        out = null
    }
}
