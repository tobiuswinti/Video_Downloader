package win.wintertools.videocatcher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import win.wintertools.videocatcher.browser.BrowserController
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.DownloadStore
import win.wintertools.videocatcher.data.Kind
import win.wintertools.videocatcher.data.Status
import win.wintertools.videocatcher.ui.App
import win.wintertools.videocatcher.ui.TAB_BROWSER
import win.wintertools.videocatcher.ui.TAB_DOWNLOADS
import win.wintertools.videocatcher.ui.TAB_LIBRARY

/**
 * Rendert die Oberflaeche ohne Geraet in PNG-Dateien (build/shots).
 * Laeuft nur mit: ./gradlew testDebugUnitTest -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun sample(): List<Download> {
        val now = System.currentTimeMillis()
        fun d(id: Long, title: String, status: Status, done: Long, total: Long, block: Download.() -> Download = { this }) =
            Download(
                id = id, url = "https://example.org/$id.mp4", title = title, fileName = "$title.mp4", path = "",
                kind = Kind.FILE, status = status, done = done, total = total, createdAt = now - id * 60_000
            ).block()
        return listOf(
            d(1, "Der lange Weg nach Norden", Status.RUNNING, 412_000_000, 913_000_000) { copy(speed = 4_300_000) },
            d(2, "Küstenwache S02E05", Status.RUNNING, 0, -1) {
                copy(kind = Kind.HLS, segDone = 212, segTotal = 640, done = 301_000_000, speed = 2_100_000, series = "Küstenwache", season = 2, episode = 5)
            },
            d(3, "Stadt ohne Schlaf", Status.PAUSED, 120_000_000, 700_000_000),
            d(4, "Ein Sommer am See", Status.ERROR, 5_000_000, 800_000_000) { copy(error = "Zugriff verweigert (HTTP 403). Der Link ist vermutlich abgelaufen.") },
            d(5, "Nachtzug", Status.DONE, 1_240_000_000, 1_240_000_000) { copy(publicUri = "content://x/5", genre = "Thriller", durationMs = 6_420_000, positionMs = 1_900_000, lastPlayedAt = now) },
            d(6, "Küstenwache S01E01", Status.DONE, 420_000_000, 420_000_000) { copy(publicUri = "content://x/6", series = "Küstenwache", season = 1, episode = 1, genre = "Krimi", durationMs = 2_700_000) },
            d(7, "Küstenwache S01E02", Status.DONE, 430_000_000, 430_000_000) { copy(publicUri = "content://x/7", series = "Küstenwache", season = 1, episode = 2, genre = "Krimi", durationMs = 2_640_000) },
            d(8, "Bergwelten", Status.DONE, 900_000_000, 900_000_000) { copy(publicUri = "content://x/8", genre = "Dokumentation", durationMs = 3_120_000) },
            d(9, "Das letzte Rennen", Status.DONE, 1_100_000_000, 1_100_000_000) { copy(publicUri = "content://x/9", genre = "Action", durationMs = 5_940_000) },
            d(10, "Kleine Fluchten", Status.DONE, 800_000_000, 800_000_000) { copy(publicUri = "content://x/10", genre = "Komödie", durationMs = 5_400_000) }
        )
    }

    private fun shoot(name: String, tab: Int, prepare: (BrowserController) -> Unit = {}) {
        var browser: BrowserController? = null
        rule.activityRule.scenario.onActivity { activity ->
            DownloadStore.init(activity)
            sample().forEach { if (DownloadStore.get(it.id) == null) DownloadStore.add(it) }
            browser = BrowserController(activity).also(prepare)
        }
        val state = mutableIntStateOf(tab)
        rule.setContent { Box(Modifier.fillMaxSize()) { App(browser!!, state, onAddFiles = {}) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test
    fun start() = shoot("1-start", TAB_BROWSER)

    @Test
    fun startWithBookmarks() = shoot("2-schnellzugriff", TAB_BROWSER) { b ->
        listOf(
            "Mediathek Beispiel" to "https://www.beispiel-mediathek.ch/sendungen",
            "Vorlesungen Physik" to "https://kurse.uni-beispiel.ch/physik",
            "Reise-Dokus" to "https://doku.example.org/reisen",
            "Sportclub Archiv" to "https://archiv.sportclub.example/videos"
        ).forEach { (t, u) -> b.bookmarks.add(BrowserController.Bookmark(t, u)) }
    }

    @Test
    fun downloads() = shoot("3-downloads", TAB_DOWNLOADS)

    @Test
    fun library() = shoot("4-mediathek", TAB_LIBRARY)
}
