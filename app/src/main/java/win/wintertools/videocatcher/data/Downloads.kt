package win.wintertools.videocatcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import win.wintertools.videocatcher.service.DownloadService
import win.wintertools.videocatcher.util.PublicStorage
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
        val d = Download(
            id = DownloadStore.nextId(),
            url = url,
            title = base,
            fileName = file.name,
            path = file.absolutePath,
            kind = kind,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie
        )
        DownloadStore.add(d)
        DownloadService.send(ctx, DownloadService.ACTION_START, d.id)
        return d.id
    }

    fun pause(ctx: Context, id: Long) = DownloadService.send(ctx, DownloadService.ACTION_PAUSE, id)

    fun resume(ctx: Context, id: Long) = DownloadService.send(ctx, DownloadService.ACTION_START, id)

    /** Entfernt den Eintrag; mit [deleteFile] auch die Datei. */
    fun remove(ctx: Context, d: Download, deleteFile: Boolean) {
        if (d.active) {
            DownloadService.send(ctx, DownloadService.ACTION_CANCEL, d.id)
            return
        }
        if (deleteFile || d.status != Status.DONE) {
            runCatching { File(d.path).delete() }
            if (deleteFile) d.publicUri?.let { PublicStorage.delete(ctx, it) }
        }
        DownloadStore.remove(d.id)
    }

    private fun uriOf(ctx: Context, d: Download): Uri? {
        d.publicUri?.let { return Uri.parse(it) }
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
