package win.wintertools.videocatcher.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/**
 * Legt fertige Dateien im oeffentlichen Speicher ab, damit sie in Galerie und
 * Dateimanager erscheinen - sortiert in Ordner:
 *   Movies/VideoCatcher/<Genre>/
 *   Movies/VideoCatcher/Serien/<Serie>/Staffel 1/
 * Braucht ab Android 10 keine Speicher-Berechtigung.
 */
object PublicStorage {

    const val FOLDER = "VideoCatcher"

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "flv" -> "video/x-flv"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "video/mp4"
    }

    private fun folderName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ")
            .trim().trim('.').take(60).trim().ifBlank { "Unbenannt" }

    /** Ordner, in den eine Datei gehoert (relativ zum Speicher, ohne Schraegstrich am Ende). */
    fun relativePath(fileName: String, genre: String?, series: String?, season: Int?): String {
        val audio = mimeOf(fileName).startsWith("audio/")
        val base = (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + FOLDER
        return when {
            !series.isNullOrBlank() -> "$base/Serien/${folderName(series)}/Staffel ${season ?: 1}"
            !genre.isNullOrBlank() -> "$base/${folderName(genre)}"
            else -> base
        }
    }

    /** Legt einen noch unsichtbaren Eintrag an, in den geschrieben werden kann. */
    fun createPending(context: Context, displayName: String, relPath: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val mime = mimeOf(displayName)
        val collection =
            if (mime.startsWith("audio/")) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            context.contentResolver.insert(collection, values)
        } catch (_: Exception) {
            null
        }
    }

    /** Macht einen mit [createPending] angelegten Eintrag sichtbar. */
    fun publish(context: Context, uri: Uri): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
        true
    } catch (_: Exception) {
        false
    }

    /** Kopiert eine Datei in den oeffentlichen Speicher. @return Adresse oder null */
    fun export(context: Context, file: File, displayName: String, relPath: String): Uri? {
        val uri = createPending(context, displayName, relPath) ?: return null
        return try {
            context.contentResolver.openOutputStream(uri).use { out ->
                requireNotNull(out)
                file.inputStream().use { it.copyTo(out, 1024 * 1024) }
            }
            if (publish(context, uri)) uri else throw IllegalStateException()
        } catch (_: Exception) {
            delete(context, uri.toString())
            null
        }
    }

    /** Verschiebt eine eigene Datei in einen anderen Ordner (ab Android 11). */
    fun move(context: Context, uri: String, relPath: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relPath) }
            context.contentResolver.update(Uri.parse(uri), values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun delete(context: Context, uri: String) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    /** Name und Groesse einer Datei hinter einer content://-Adresse. */
    fun nameAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return name to size
    }
}
