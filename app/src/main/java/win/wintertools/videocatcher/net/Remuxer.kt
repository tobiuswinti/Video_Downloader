package win.wintertools.videocatcher.net

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Packt eine .ts-Datei (HLS) ohne Neukodierung in eine .mp4-Datei um, damit
 * sie in Galerie und Standard-Player laeuft. Nutzt nur Android-Bordmittel.
 * Klappt das nicht, bleibt die .ts-Datei erhalten.
 */
object Remuxer {

    fun tsToMp4(src: File, dst: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            extractor.setDataSource(src.absolutePath)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = HashMap<Int, Int>()
            var bufferSize = 4 * 1024 * 1024
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/")) hasVideo = true
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (trackMap.isEmpty() || !hasVideo) return false

            muxer.start()
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = trackMap[extractor.sampleTrackIndex]
                if (track != null) {
                    info.set(
                        0, size, extractor.sampleTime,
                        extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                    )
                    muxer.writeSampleData(track, buffer, info)
                    samples++
                }
                if (!extractor.advance()) break
            }
            muxer.stop()
            // Plausibilitaet: eine deutlich kleinere Datei heisst, es fehlt etwas
            ok = samples > 0 && dst.length() > src.length() / 2
        } catch (_: Throwable) {
            ok = false
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            if (!ok) dst.delete()
        }
        return ok
    }
}
