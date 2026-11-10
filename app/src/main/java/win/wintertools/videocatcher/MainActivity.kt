package win.wintertools.videocatcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import win.wintertools.videocatcher.browser.BrowserController
import win.wintertools.videocatcher.data.DownloadStore
import win.wintertools.videocatcher.service.DownloadService
import win.wintertools.videocatcher.ui.App
import win.wintertools.videocatcher.ui.TAB_BROWSER
import win.wintertools.videocatcher.ui.TAB_DOWNLOADS
import win.wintertools.videocatcher.util.Notify

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_SHOW_DOWNLOADS = "win.wintertools.videocatcher.SHOW_DOWNLOADS"
    }

    private lateinit var browser: BrowserController
    private val tab = mutableIntStateOf(TAB_BROWSER)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadStore.init(this)
        Notify.channels(this)

        browser = BrowserController(this)
        setContent { App(browser, tab) }
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Downloads, die beim letzten Beenden noch liefen, wieder aufnehmen
        if (DownloadStore.items.value.any { it.active }) {
            DownloadService.send(this, DownloadService.ACTION_RESUME_ALL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHOW_DOWNLOADS -> tab.intValue = TAB_DOWNLOADS
            Intent.ACTION_SEND -> {
                // Aus einer anderen App geteilter Text: erste Adresse darin oeffnen
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val url = Regex("https?://\\S+").find(text)?.value
                if (url != null) {
                    tab.intValue = TAB_BROWSER
                    browser.load(url)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        browser.main.onResume()
    }

    override fun onPause() {
        browser.main.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }
}
