package win.wintertools.videocatcher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.DownloadStore
import win.wintertools.videocatcher.data.Downloads
import java.io.File

/**
 * Vorschaubilder fuer die Mediathek: ein Standbild aus dem Video selbst,
 * einmal erzeugt und als kleine JPEG-Datei gespeichert.
 */
object Thumbs {

    private val memory = LruCache<Long, ImageBitmap>(80)
    private val gate = Semaphore(2)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "thumbs").apply { mkdirs() }

    fun cached(id: Long): ImageBitmap? = memory.get(id)

    suspend fun load(ctx: Context, d: Download): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(d.id)?.let { return@withContext it }
        val file = File(dir(ctx), "${d.id}.jpg")
        if (!file.exists()) gate.withPermit { if (!file.exists()) create(ctx, d, file) }
        if (file.length() == 0L) return@withContext null
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        if (bmp != null) memory.put(d.id, bmp)
        bmp
    }

    private fun create(ctx: Context, d: Download, target: File) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = Downloads.uriOf(ctx, d)
            if (uri == null) {
                target.writeBytes(ByteArray(0))
                return
            }
            retriever.setDataSource(ctx, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration > 0 && d.durationMs == 0L) {
                DownloadStore.update(d.id, force = true) { it.copy(durationMs = duration) }
            }
            // Ein Bild etwas nach dem Anfang: der Vorspann ist oft schwarz
            val atMs = if (duration > 0) minOf(duration * 15 / 100, 5 * 60_000L) else 3000L
            val frame = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1)
            if (frame == null) {
                target.writeBytes(ByteArray(0))
                return
            }
            val scale = 480f / maxOf(frame.width, 1)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, 480, (frame.height * scale).toInt().coerceAtLeast(1), true)
            } else frame
            target.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (_: Throwable) {
            // Leere Datei merkt sich: fuer dieses Video gibt es kein Bild
            runCatching { target.writeBytes(ByteArray(0)) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun delete(ctx: Context, id: Long) {
        memory.remove(id)
        runCatching { File(dir(ctx), "$id.jpg").delete() }
    }
}
