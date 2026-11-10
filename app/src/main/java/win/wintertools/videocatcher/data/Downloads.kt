package win.wintertools.videocatcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import win.wintertools.videocatcher.service.DownloadService
import win.wintertools.videocatcher.util.PublicStorage
import win.wintertools.videocatcher.util.Thumbs
import java.io.File

/** Aktionen auf Downloads, wie sie die Oberflaeche braucht. */
object Downloads {

    private fun workDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads").apply { mkdirs() }

    private fun cleanName(raw: String): String {
        val s = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim().trim('.')
        return s.take(70).trim()
    }

    private fun uniqueFile(ctx: Context, base: String, ext: String): File {
        val dir = workDir(ctx)
        val taken = DownloadStore.items.value.map { it.fileName.substringBeforeLast('.') }.toSet()
        var name = base
        var i = 2
        while (name in taken || File(dir, "$name.$ext").exists()) {
            name = "$base ($i)"
            i++
        }
        return File(dir, "$name.$ext")
    }

    fun start(
        ctx: Context,
        url: String,
        kind: Kind,
        ext: String,
        title: String?,
        referer: String?,
        userAgent: String?,
        cookie: String?
    ): Long {
        DownloadStore.init(ctx)
        val fromUrl = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringBeforeLast('.')
        val base = cleanName(title.orEmpty()).ifBlank { cleanName(Uri.decode(fromUrl)) }.ifBlank { "Video" }
        val file = uniqueFile(ctx, base, ext)
        val parsed = TitleParser.parse(base)
        val d = Download(
            id = DownloadStore.nextId(),
            url = url,
            title = base,
            fileName = file.name,
            path = file.absolutePath,
            kind = kind,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            series = parsed.series,
            season = parsed.season,
            episode = parsed.episode
        )
        DownloadStore.add(d)
        DownloadService.send(ctx, DownloadService.ACTION_START, d.id)
        return d.id
    }

    fun pause(ctx: Context, id: Long) = DownloadService.send(ctx, DownloadService.ACTION_PAUSE, id)

    fun resume(ctx: Context, id: Long) = DownloadService.send(ctx, DownloadService.ACTION_START, id)

    /**
     * Entfernt den Eintrag; mit [deleteFile] auch die Datei.
     * Von Hand hinzugefuegte Dateien werden nie geloescht, nur aus der Liste genommen.
     */
    fun remove(ctx: Context, d: Download, deleteFile: Boolean) {
        if (d.active) {
            DownloadService.send(ctx, DownloadService.ACTION_CANCEL, d.id)
            return
        }
        if (d.manual) {
            d.publicUri?.let {
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        } else if (deleteFile || d.status != Status.DONE) {
            if (d.path.isNotEmpty()) runCatching { File(d.path).delete() }
            d.pendingUri?.let { PublicStorage.delete(ctx, it) }
            if (deleteFile) d.publicUri?.let { PublicStorage.delete(ctx, it) }
        }
        Thumbs.delete(ctx, d.id)
        DownloadStore.remove(d.id)
    }

    /** Vorhandene Videodateien (z. B. MP4) in die Mediathek aufnehmen - ohne sie zu kopieren. */
    fun addManual(ctx: Context, uris: List<Uri>): Int {
        DownloadStore.init(ctx)
        var added = 0
        for (uri in uris) {
            val key = uri.toString()
            if (DownloadStore.items.value.any { it.publicUri == key }) continue
            // Zugriff dauerhaft behalten (auch nach einem Neustart)
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (name, size) = PublicStorage.nameAndSize(ctx, uri)
            val fileName = name ?: "Video.mp4"
            val title = fileName.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { "Video" }
            val parsed = TitleParser.parse(title)
            DownloadStore.add(
                Download(
                    id = DownloadStore.nextId(),
                    url = key,
                    title = title,
                    fileName = fileName,
                    path = "",
                    kind = Kind.FILE,
                    status = Status.DONE,
                    total = size,
                    done = size,
                    publicUri = key,
                    manual = true,
                    series = parsed.series,
                    season = parsed.season,
                    episode = parsed.episode
                )
            )
            added++
        }
        return added
    }

    /**
     * Einordnung aendern. Bei Serien gilt das Genre fuer alle Folgen.
     * Eigene Dateien werden dabei in den passenden Ordner verschoben.
     */
    fun setMeta(ctx: Context, id: Long, title: String, genre: String?, series: String?, season: Int?, episode: Int?) {
        val t = title.trim().ifBlank { "Video" }
        val g = genre?.trim()?.takeIf { it.isNotEmpty() }
        val s = series?.trim()?.takeIf { it.isNotEmpty() }
        DownloadStore.update(id, force = true) {
            it.copy(
                title = t, genre = g, series = s,
                season = if (s != null) (season ?: 1) else null,
                episode = if (s != null) (episode ?: 1) else null
            )
        }
        val changed = ArrayList<Long>()
        changed.add(id)
        if (s != null) {
            DownloadStore.items.value.filter { it.id != id && it.series.equals(s, ignoreCase = true) }.forEach { other ->
                DownloadStore.update(other.id, force = true) { it.copy(genre = g, series = s) }
                changed.add(other.id)
            }
        }
        // Dateien in den passenden Ordner legen (nur eigene, ab Android 11)
        val app = ctx.applicationContext
        Thread {
            for (cid in changed) {
                val d = DownloadStore.get(cid) ?: continue
                val uri = d.publicUri ?: continue
                if (d.manual) continue
                PublicStorage.move(app, uri, PublicStorage.relativePath(d.fileName, d.genre, d.series, d.season))
            }
        }.start()
    }

    fun savePosition(id: Long, positionMs: Long, durationMs: Long, final: Boolean) {
        DownloadStore.update(id, force = final) {
            // Fast zu Ende geschaut: beim naechsten Mal wieder von vorn
            val finished = durationMs > 0 && positionMs > durationMs - 20_000
            it.copy(
                positionMs = if (finished) 0L else positionMs,
                durationMs = if (durationMs > 0) durationMs else it.durationMs,
                lastPlayedAt = System.currentTimeMillis()
            )
        }
    }

    fun uriOf(ctx: Context, d: Download): Uri? {
        d.publicUri?.let { return Uri.parse(it) }
        if (d.path.isEmpty()) return null
        val f = File(d.path)
        if (!f.exists()) return null
        return runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) }.getOrNull()
    }

    fun open(ctx: Context, d: Download) {
        val uri = uriOf(ctx, d)
        if (uri == null) {
            Toast.makeText(ctx, "Datei nicht mehr vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, PublicStorage.mimeOf(d.fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(view, "Öffnen mit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Keine App zum Abspielen gefunden", Toast.LENGTH_LONG).show()
        }
    }

    fun share(ctx: Context, d: Download) {
        val uri = uriOf(ctx, d) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(PublicStorage.mimeOf(d.fileName))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            ctx.startActivity(Intent.createChooser(send, "Teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
