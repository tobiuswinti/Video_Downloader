package win.wintertools.videocatcher.data

import org.json.JSONObject

enum class Status { QUEUED, RUNNING, PAUSED, DONE, ERROR }

enum class Kind {
    /** Direkte Datei (mp4, webm, ...) */
    FILE,

    /** HLS-Stream (m3u8-Playlist mit Segmenten) */
    HLS
}

data class Download(
    val id: Long,
    val url: String,
    val title: String,
    val fileName: String,
    /** Arbeitsdatei im App-Ordner */
    val path: String,
    val kind: Kind,
    val status: Status = Status.QUEUED,
    val total: Long = -1L,
    val done: Long = 0L,
    val segTotal: Int = 0,
    val segDone: Int = 0,
    val speed: Long = 0L,
    val error: String? = null,
    val note: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    /** Ort der fertigen Datei im oeffentlichen Speicher (content://...), falls exportiert */
    val publicUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val active: Boolean get() = status == Status.RUNNING || status == Status.QUEUED

    /** 0..1 oder null, wenn die Gesamtgroesse nicht bekannt ist */
    val fraction: Float?
        get() = when {
            status == Status.DONE -> 1f
            kind == Kind.HLS && segTotal > 0 -> (segDone.toFloat() / segTotal).coerceIn(0f, 1f)
            total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
            else -> null
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("fileName", fileName)
        put("path", path)
        put("kind", kind.name)
        put("status", status.name)
        put("total", total)
        put("done", done)
        put("segTotal", segTotal)
        put("segDone", segDone)
        putOpt("error", error)
        putOpt("note", note)
        putOpt("referer", referer)
        putOpt("userAgent", userAgent)
        putOpt("cookie", cookie)
        putOpt("publicUri", publicUri)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Download = Download(
            id = o.getLong("id"),
            url = o.getString("url"),
            title = o.optString("title", "Video"),
            fileName = o.optString("fileName", "video.mp4"),
            path = o.getString("path"),
            kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.FILE),
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PAUSED),
            total = o.optLong("total", -1L),
            done = o.optLong("done", 0L),
            segTotal = o.optInt("segTotal", 0),
            segDone = o.optInt("segDone", 0),
            error = o.optStringOrNull("error"),
            note = o.optStringOrNull("note"),
            referer = o.optStringOrNull("referer"),
            userAgent = o.optStringOrNull("userAgent"),
            cookie = o.optStringOrNull("cookie"),
            publicUri = o.optStringOrNull("publicUri"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
