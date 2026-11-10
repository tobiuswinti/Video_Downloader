package win.wintertools.videocatcher.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.DownloadStore
import win.wintertools.videocatcher.data.Kind
import win.wintertools.videocatcher.data.Status
import win.wintertools.videocatcher.net.FileDownloader
import win.wintertools.videocatcher.net.HlsDownloader
import win.wintertools.videocatcher.net.LiveRemuxer
import win.wintertools.videocatcher.net.MuxOutput
import win.wintertools.videocatcher.net.Remuxer
import win.wintertools.videocatcher.util.Notify
import win.wintertools.videocatcher.util.PublicStorage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Vordergrund-Dienst: haelt die Downloads am Laufen, auch wenn die App im
 * Hintergrund oder der Bildschirm aus ist. Bis zu [MAX_PARALLEL] gleichzeitig,
 * weitere warten in der Schlange.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_RESUME_ALL = "resume_all"
        const val EXTRA_ID = "id"
        const val MAX_PARALLEL = 3

        fun send(ctx: Context, action: String, id: Long = -1L) {
            val i = Intent(ctx, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
                // Start aus dem Hintergrund nicht erlaubt - beim naechsten Oeffnen geht es weiter
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val gate = Semaphore(MAX_PARALLEL)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(this)
        Notify.channels(this)
        // Benachrichtigung einmal pro Sekunde auffrischen
        scope.launch {
            while (isActive) {
                delay(1000)
                if (jobs.isNotEmpty()) showProgress()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showProgress()
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> startJob(id)
            ACTION_PAUSE -> {
                jobs[id]?.cancel()
                DownloadStore.update(id, force = true) {
                    if (it.status == Status.DONE) it else it.copy(status = Status.PAUSED, speed = 0, note = null)
                }
            }
            ACTION_CANCEL -> cancelAndDelete(id)
            // null = der Dienst wurde vom System neu gestartet
            ACTION_RESUME_ALL, null -> DownloadStore.items.value.filter { it.active }.forEach { startJob(it.id) }
        }
        stopIfIdle()
        return START_STICKY
    }

    private fun startJob(id: Long) {
        val d = DownloadStore.get(id) ?: return
        if (d.status == Status.DONE) return
        val previous = jobs[id]
        if (previous != null && previous.isActive) return

        DownloadStore.update(id, force = true) { it.copy(status = Status.QUEUED, error = null, speed = 0) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                // Ein gerade pausierter Lauf muss erst die Datei loslassen
                previous?.join()
                gate.withPermit {
                    val current = DownloadStore.get(id) ?: return@withPermit
                    DownloadStore.update(id, force = true) { it.copy(status = Status.RUNNING) }
                    acquireLocks()
                    // Rest eines frueheren, abgebrochenen Laufs wegraeumen
                    current.pendingUri?.let { PublicStorage.delete(this@DownloadService, it) }
                    var live: LiveRemuxer? = null
                    val file = try {
                        when (current.kind) {
                            Kind.HLS -> {
                                live = newLiveRemuxer(current)
                                HlsDownloader.run(current, live)
                            }
                            Kind.FILE -> {
                                FileDownloader.run(current)
                                File(current.path)
                            }
                        }
                    } catch (t: Throwable) {
                        // Pause, Abbruch oder Fehler: angefangene MP4 verwerfen
                        live?.abandon()
                        throw t
                    }
                    finish(id, file, live)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                DownloadStore.update(id, force = true) { it.copy(status = Status.ERROR, error = msg, speed = 0) }
                DownloadStore.get(id)?.let { Notify.finished(this@DownloadService, id, false, it.title, msg) }
            } finally {
                jobs.remove(id, self)
                stopIfIdle()
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun workDir(): File = File(getExternalFilesDir(null) ?: filesDir, "downloads")

    private fun relPath(d: Download, name: String): String =
        PublicStorage.relativePath(name, d.genre, d.series, d.season)

    /** Zieldatei wird erst angelegt, wenn das erste Segment da ist. */
    private fun newLiveRemuxer(d: Download): LiveRemuxer {
        val mp4 = d.fileName.substringBeforeLast('.') + ".mp4"
        return LiveRemuxer(File(d.path)) {
            MuxOutput.create(this, mp4, relPath(d, mp4), workDir())?.also { out ->
                DownloadStore.update(d.id, force = true) { it.copy(pendingUri = out.uri?.toString()) }
            }
        }
    }

    /** Abschluss: MP4 fertigstellen und Datei in den oeffentlichen Speicher legen. */
    private fun finish(id: Long, downloaded: File, live: LiveRemuxer?) {
        var file = downloaded
        val d = DownloadStore.get(id)
        if (d == null) {
            live?.abandon()
            return
        }
        val keepNote = d.note?.takeIf { it.startsWith("Ton liegt") }

        // Normalfall bei Streams: die MP4 ist waehrend des Ladens schon entstanden
        if (live != null && file.name.endsWith(".ts")) {
            DownloadStore.update(id) { it.copy(speed = 0, note = "Wird abgeschlossen…") }
            val out = live.finish(d.durationMs * 1000)
            if (out != null) {
                file.delete()
                DownloadStore.update(id, force = true) {
                    it.copy(
                        status = Status.DONE, speed = 0, error = null, note = keepNote,
                        path = out.file?.absolutePath.orEmpty(), fileName = out.name,
                        done = out.size, total = out.size,
                        publicUri = out.uri?.toString(), pendingUri = null
                    )
                }
                Notify.finished(this, id, true, d.title, out.name)
                return
            }
        } else {
            live?.abandon()
        }

        // Ausweichweg: nach dem Laden umwandeln
        if (d.kind == Kind.HLS && file.name.endsWith(".ts")) {
            DownloadStore.update(id) { it.copy(speed = 0, note = "Wird in MP4 umgewandelt…") }
            val mp4 = File(file.parentFile, file.name.removeSuffix(".ts") + ".mp4")
            if (Remuxer.tsToMp4(file, mp4, d.durationMs * 1000)) {
                file.delete()
                file = mp4
            }
        }

        DownloadStore.update(id) { it.copy(note = "Wird gespeichert…") }
        val uri = PublicStorage.export(this, file, file.name, relPath(d, file.name))
        val size = file.length()
        if (uri != null) file.delete()

        DownloadStore.update(id, force = true) {
            it.copy(
                status = Status.DONE, speed = 0, error = null, note = keepNote,
                path = if (uri != null) "" else file.absolutePath, fileName = file.name,
                done = size, total = size, publicUri = uri?.toString(), pendingUri = null
            )
        }
        Notify.finished(this, id, true, d.title, file.name)
    }

    private fun cancelAndDelete(id: Long) {
        val job = jobs[id]
        val d = DownloadStore.get(id)
        DownloadStore.remove(id)
        scope.launch {
            job?.cancel()
            job?.join()
            if (d != null) {
                d.pendingUri?.let { PublicStorage.delete(this@DownloadService, it) }
                if (d.path.isNotEmpty()) runCatching { File(d.path).delete() }
                // Pfad kann sich waehrend des Ladens geaendert haben (.ts -> .mp4)
                runCatching { File(d.path.removeSuffix(".ts") + ".mp4").takeIf { d.path.endsWith(".ts") }?.delete() }
            }
            stopIfIdle()
        }
    }

    private fun showProgress() {
        val running = DownloadStore.items.value.filter { it.status == Status.RUNNING }
        val queued = DownloadStore.items.value.count { it.status == Status.QUEUED }
        val title = when {
            running.isEmpty() && queued == 0 -> "VideoCatcher"
            running.size == 1 -> running[0].title
            else -> "${running.size} Downloads laufen"
        }
        val fractions = running.mapNotNull { it.fraction }
        val percent = if (fractions.isNotEmpty() && fractions.size == running.size) {
            (fractions.average() * 100).toInt()
        } else null
        val speed = running.sumOf { it.speed }
        val text = buildString {
            if (percent != null) append("$percent %")
            if (speed > 0) {
                if (isNotEmpty()) append(" · ")
                append(win.wintertools.videocatcher.browser.Format.bytes(speed)).append("/s")
            }
            if (queued > 0) {
                if (isNotEmpty()) append(" · ")
                append("$queued in Warteschlange")
            }
            if (isEmpty()) append("Wird vorbereitet…")
        }
        val n = Notify.progress(this, title, text, percent)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, Notify.ID_FOREGROUND, n, type)
            foreground = true
        } catch (_: Exception) {
            // Vom System nicht erlaubt (z. B. Neustart im Hintergrund): ohne Benachrichtigung weiter
        }
    }

    @Synchronized
    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) return
        releaseLocks()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    @Synchronized
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoCatcher:download")
                    .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
            }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VideoCatcher:wifi")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    @Synchronized
    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }
}
