package win.wintertools.videocatcher.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Legt fertige Dateien im oeffentlichen Speicher ab, damit sie in Galerie und
 * Dateimanager erscheinen: Videos unter Movies/VideoCatcher, Ton unter
 * Music/VideoCatcher. Braucht ab Android 10 keine Speicher-Berechtigung.
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

    /** @return Adresse der exportierten Datei oder null (dann bleibt die Datei im App-Ordner) */
    fun export(context: Context, file: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val mime = mimeOf(displayName)
        val (collection, dir) = when {
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out)
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun delete(context: Context, uri: String) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
    }
}
