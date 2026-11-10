package win.wintertools.videocatcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Die Liste aller Downloads. Lebt im Speicher (StateFlow fuer die Oberflaeche)
 * und wird als JSON-Datei gesichert, damit sie einen Neustart uebersteht.
 */
object DownloadStore {

    private val state = MutableStateFlow<List<Download>>(emptyList())
    val items: StateFlow<List<Download>> = state

    private val writer = Executors.newSingleThreadExecutor()
    private val lastId = AtomicLong(0)
    private var file: File? = null

    @Volatile
    private var lastSave = 0L

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "downloads.json")
        file = f
        val loaded = runCatching {
            if (!f.exists()) emptyList()
            else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { Download.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())
        state.value = loaded
        lastId.set(loaded.maxOfOrNull { it.id } ?: 0L)
    }

    fun nextId(): Long = lastId.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    fun get(id: Long): Download? = state.value.firstOrNull { it.id == id }

    fun add(d: Download) {
        state.update { listOf(d) + it }
        save(force = true)
    }

    /** [force] = sofort sichern (Statuswechsel); sonst hoechstens alle zwei Sekunden. */
    fun update(id: Long, force: Boolean = false, change: (Download) -> Download) {
        state.update { list -> list.map { if (it.id == id) change(it) else it } }
        save(force)
    }

    fun remove(id: Long) {
        state.update { list -> list.filterNot { it.id == id } }
        save(force = true)
    }

    private fun save(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 2000) return
        lastSave = now
        val target = file ?: return
        writer.execute {
            runCatching {
                val arr = JSONArray()
                state.value.forEach { arr.put(it.toJson()) }
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(target)) {
                    target.writeText(arr.toString())
                    tmp.delete()
                }
            }
        }
    }
}
