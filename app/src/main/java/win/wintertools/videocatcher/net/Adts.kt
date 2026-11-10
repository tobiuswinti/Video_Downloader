package win.wintertools.videocatcher.net

import java.nio.ByteBuffer

/**
 * AAC-Ton steckt in TS-Streams in ADTS-Rahmen: jedem Tonstueck geht ein Kopf
 * von 7 oder 9 Bytes voraus. In einer MP4-Datei darf dieser Kopf nicht stehen,
 * sonst bleibt der Ton stumm oder stoert. Diese Hilfe erkennt den Kopf.
 */
object Adts {

    /**
     * @return Anzahl Bytes, die am Anfang des Tonstuecks zu ueberspringen sind (0, 7 oder 9)
     */
    fun headerLength(buf: ByteBuffer, size: Int): Int {
        if (size < 7) return 0
        val b0 = buf.get(0).toInt() and 0xFF
        val b1 = buf.get(1).toInt() and 0xFF
        // Kennung 0xFFF, Layer-Bits 00
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return 0
        val b3 = buf.get(3).toInt() and 0xFF
        val b4 = buf.get(4).toInt() and 0xFF
        val b5 = buf.get(5).toInt() and 0xFF
        // Die im Kopf vermerkte Laenge muss genau zum Stueck passen - sonst ist es kein ADTS-Kopf
        val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or (b5 shr 5)
        if (frameLength != size) return 0
        val header = if (b1 and 0x01 == 1) 7 else 9
        return if (size > header) header else 0
    }
}
