package win.wintertools.videocatcher.net

import java.nio.ByteBuffer

/**
 * AAC-Ton steckt in TS-Streams in ADTS-Rahmen: jedem Tonrahmen geht ein Kopf
 * von 7 oder 9 Bytes voraus, und ein Tonstueck kann mehrere Rahmen enthalten.
 * In einer MP4-Datei muss jeder Rahmen einzeln und ohne Kopf stehen, sonst
 * bleibt der Ton stumm. Diese Hilfe zerlegt ein Tonstueck entsprechend.
 */
object Adts {

    /** Ein roher AAC-Rahmen innerhalb des Tonstuecks */
    class Frame(val offset: Int, val length: Int)

    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /**
     * @return die Rahmen ohne Kopf, oder null, wenn das Stueck nicht lueckenlos aus ADTS-Rahmen besteht
     *         (dann ist es schon rohes AAC und wird unveraendert uebernommen)
     */
    fun frames(buf: ByteBuffer, size: Int): List<Frame>? {
        val out = ArrayList<Frame>(2)
        var pos = 0
        while (pos < size) {
            if (size - pos < 7) return null
            val b0 = buf.get(pos).toInt() and 0xFF
            val b1 = buf.get(pos + 1).toInt() and 0xFF
            // Kennung 0xFFF, Layer-Bits 00
            if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null
            val b3 = buf.get(pos + 3).toInt() and 0xFF
            val b4 = buf.get(pos + 4).toInt() and 0xFF
            val b5 = buf.get(pos + 5).toInt() and 0xFF
            val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or (b5 shr 5)
            val header = if (b1 and 0x01 == 1) 7 else 9
            if (frameLength <= header || pos + frameLength > size) return null
            out.add(Frame(pos + header, frameLength - header))
            pos += frameLength
        }
        return if (out.isEmpty()) null else out
    }

    /** Dauer eines Rahmens in Mikrosekunden (1024 Abtastwerte), aus dem ersten Kopf gelesen. */
    fun frameDurationUs(buf: ByteBuffer): Long {
        val index = ((buf.get(2).toInt() and 0xFF) shr 2) and 0x0F
        val rate = rates.getOrElse(index) { 44100 }
        return 1024L * 1_000_000L / rate
    }
}
