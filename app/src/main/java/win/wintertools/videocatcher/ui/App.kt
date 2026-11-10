package win.wintertools.videocatcher.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import win.wintertools.videocatcher.browser.BrowserController
import win.wintertools.videocatcher.data.DownloadStore

const val TAB_BROWSER = 0
const val TAB_DOWNLOADS = 1
const val TAB_LIBRARY = 2

@Composable
fun App(browser: BrowserController, tab: MutableIntState, onAddFiles: () -> Unit) {
    val downloads by DownloadStore.items.collectAsState()
    val active = downloads.count { it.active }
    val fullscreen = browser.fullscreenView
    val library = remember { LibraryState() }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }

    VcTheme {
        Box(Modifier.fillMaxSize().background(Vc.Bg)) {
            Scaffold(
                containerColor = Vc.Bg,
                bottomBar = {
                    NavigationBar(containerColor = Vc.Surface) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Vc.OnAccent,
                            selectedTextColor = Vc.Accent,
                            indicatorColor = Vc.Accent,
                            unselectedIconColor = Vc.Text,
                            unselectedTextColor = Vc.Text
                        )
                        NavigationBarItem(
                            selected = tab.intValue == TAB_BROWSER,
                            onClick = { tab.intValue = TAB_BROWSER },
                            icon = { Text("◎", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                            label = { Text("Browser") },
                            colors = colors
                        )
                        NavigationBarItem(
                            selected = tab.intValue == TAB_DOWNLOADS,
                            onClick = { tab.intValue = TAB_DOWNLOADS },
                            icon = {
                                BadgedBox(badge = {
                                    if (active > 0) Badge(containerColor = Vc.Ok, contentColor = Color.Black) {
                                        Text("$active")
                                    }
                                }) { Text("↓", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                            },
                            label = { Text("Downloads") },
                            colors = colors
                        )
                        NavigationBarItem(
                            selected = tab.intValue == TAB_LIBRARY,
                            onClick = {
                                // Nochmal antippen fuehrt von der Serienseite zurueck zur Uebersicht
                                if (tab.intValue == TAB_LIBRARY) library.openSeries = null
                                tab.intValue = TAB_LIBRARY
                            },
                            icon = { Text("▶", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                            label = { Text("Mediathek") },
                            colors = colors
                        )
                    }
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    // Der Browser bleibt immer bestehen; die anderen Reiter liegen nur darueber.
                    // So wird die Seite beim Hin- und Herwechseln nicht neu geladen.
                    BrowserScreen(browser, onShowDownloads = { tab.intValue = TAB_DOWNLOADS })
                    when (tab.intValue) {
                        TAB_DOWNLOADS -> DownloadsScreen(downloads.filterNot { it.manual })
                        TAB_LIBRARY -> LibraryScreen(
                            all = downloads,
                            state = library,
                            onPlay = { playing = it },
                            onAddFiles = onAddFiles
                        )
                    }
                }
            }

            if (fullscreen != null) {
                key(fullscreen) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        factory = {
                            (fullscreen.parent as? ViewGroup)?.removeView(fullscreen)
                            fullscreen
                        }
                    )
                }
            }

            playing?.let { request ->
                key(request) { PlayerScreen(request, onClose = { playing = null }) }
            }
        }

        BackHandler(
            enabled = playing != null || fullscreen != null || tab.intValue != TAB_BROWSER ||
                browser.popups.isNotEmpty() || browser.canGoBack
        ) {
            when {
                playing != null -> playing = null
                fullscreen != null -> browser.exitFullscreen()
                tab.intValue == TAB_LIBRARY && library.openSeries != null -> library.openSeries = null
                tab.intValue == TAB_LIBRARY && library.query.isNotEmpty() -> library.query = ""
                tab.intValue != TAB_BROWSER -> tab.intValue = TAB_BROWSER
                browser.popups.isNotEmpty() -> browser.closeTopPopup()
                else -> browser.back()
            }
        }
    }
}
