package win.wintertools.videocatcher.browser

import win.wintertools.videocatcher.data.Kind
import win.wintertools.videocatcher.net.HlsDownloader
import win.wintertools.videocatcher.net.Http
import java.util.Locale

/** Ein auf der Seite gefundenes Video. */
data class MediaHit(
    val url: String,
    val kind: Kind,
    /** Dateiendung ohne Punkt */
    val ext: String,
    val referer: String?,
    /** Schluessel zum Erkennen von Doppelten (Adresse ohne Parameter) */
    val key: String
)

/**
 * Erkennt Video-Adressen unter allen Anfragen, die eine Seite stellt.
 * Drei Wege: Dateiendung, typische Anfrage eines Video-Elements, Suche im Seiteninhalt.
 */
object MediaSniffer {

    private val mediaExt = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "avi", "3gp", "mp3", "m4a", "ogg", "opus", "wav")

    /** Einzelteile eines Streams - die gehoeren nicht in die Liste */
    private val segmentExt = setOf("ts", "m4s", "aac", "vtt", "srt", "key")

    private val otherExt = setOf(
        "js", "css", "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "ico", "woff", "woff2", "ttf",
        "json", "html", "htm", "php", "xml", "txt", "map", "wasm"
    )

    /**
     * @param headers Anfrage-Kopfzeilen aus der WebView (oder null)
     * @param knownMedia true, wenn die Adresse aus einem video-/audio-Element stammt
     */
    fun classify(url: String, headers: Map<String, String>?, knownMedia: Boolean = false): MediaHit? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val noQuery = url.substringBefore('#').substringBefore('?')
        val path = noQuery.lowercase(Locale.ROOT)
        val name = path.substringAfterLast('/')
        val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
        val referer = headers?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value

        return when {
            ext == "m3u8" -> MediaHit(url, Kind.HLS, "ts", referer, noQuery)
            ext == "mpd" -> null // MPEG-DASH wird nicht unterstuetzt
            ext in segmentExt -> null
            ext in mediaExt -> MediaHit(url, Kind.FILE, ext, referer, noQuery)
            knownMedia -> MediaHit(url, Kind.FILE, "mp4", referer, noQuery)
            isMediaElementRequest(headers) -> MediaHit(url, Kind.FILE, "mp4", referer, noQuery)
            ext in otherExt -> null
            path.contains("/videoplayback") || url.contains("mime=video", true) ->
                MediaHit(url, Kind.FILE, "mp4", referer, noQuery)
            else -> null
        }
    }

    /**
     * Ein video-Element fragt mit "Accept-Encoding: identity" und einem Range-Kopf an.
     * Daran erkennt man Videos auch ohne Dateiendung in der Adresse.
     */
    private fun isMediaElementRequest(headers: Map<String, String>?): Boolean {
        if (headers == null) return false
        val enc = headers.entries.firstOrNull { it.key.equals("Accept-Encoding", true) }?.value ?: return false
        return enc.trim().startsWith("identity")
    }

    /** Im Seiteninhalt nach video-/audio-Elementen suchen (liefert ein JSON-Array). */
    const val DOM_SCAN_JS = """
(function(){
  var out=[];
  function add(s){ if(s && /^https?:/i.test(s) && out.indexOf(s)<0) out.push(s); }
  try{
    document.querySelectorAll('video,audio,source').forEach(function(e){
      add(e.currentSrc); add(e.src); add(e.getAttribute('data-src'));
    });
    document.querySelectorAll('a[href]').forEach(function(a){
      if(/\.(mp4|m4v|webm|mkv|mov|m3u8)(\?|#|$)/i.test(a.href)) add(a.href);
    });
  }catch(e){}
  return JSON.stringify(out);
})()
"""

    /** Kurze Beschreibung fuer die Liste: Groesse, Laenge oder Qualitaet. */
    suspend fun describe(hit: MediaHit, referer: String?, ua: String?, cookie: String?): String {
        return try {
            if (hit.kind == Kind.HLS) describeHls(hit, referer, ua, cookie)
            else describeFile(hit, referer, ua, cookie)
        } catch (_: Exception) {
            if (hit.kind == Kind.HLS) "Stream" else hit.ext.uppercase(Locale.ROOT)
        }
    }

    private suspend fun describeFile(hit: MediaHit, referer: String?, ua: String?, cookie: String?): String {
        val req = Http.request(hit.url, referer, ua, cookie, range = "bytes=0-0")
        return Http.call(req) { resp ->
            val type = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            if (!resp.isSuccessful) return@call "Nicht abrufbar (HTTP ${resp.code})"
            if (type.startsWith("text/")) return@call "Kein Video (Webseite)"
            val total = resp.header("Content-Range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
                ?: resp.body?.contentLength()?.takeIf { resp.code == 200 && it > 0 }
            val label = hit.ext.uppercase(Locale.ROOT)
            if (total != null && total > 0) "$label · ${Format.bytes(total)}" else label
        }
    }

    private suspend fun describeHls(hit: MediaHit, referer: String?, ua: String?, cookie: String?): String {
        val text = HlsDownloader.fetchText(hit.url, referer, ua, cookie)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val best = HlsDownloader.variants(text).maxOfOrNull { it.height } ?: 0
            return if (best > 0) "Stream · beste Qualität ${best}p" else "Stream · beste Qualität"
        }
        if (Regex("#EXT-X-KEY:[^\\n]*METHOD=(?!NONE)").containsMatchIn(text)) return "Stream · verschlüsselt (nicht ladbar)"
        if (!text.contains("#EXT-X-ENDLIST")) return "Livestream (nicht ladbar)"
        val seconds = Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        return if (seconds > 0) "Stream · ${Format.duration(seconds.toLong())}" else "Stream"
    }
}

object Format {
    fun bytes(b: Long): String {
        if (b <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${b} B" else String.format(Locale.GERMANY, "%.1f %s", v, units[i])
    }

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d Std.", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d Min.", m, s)
    }
}
