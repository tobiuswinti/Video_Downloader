package win.wintertools.videocatcher.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    BottomBar(
                        selected = tab.intValue,
                        activeDownloads = active,
                        onSelect = { target ->
                            // Nochmal antippen fuehrt von der Serienseite zurueck zur Uebersicht
                            if (target == TAB_LIBRARY && tab.intValue == TAB_LIBRARY) library.openSeries = null
                            tab.intValue = target
                        }
                    )
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    // Der Browser bleibt immer bestehen; die anderen Reiter liegen nur darueber.
                    // So wird die Seite beim Hin- und Herwechseln nicht neu geladen.
                    BrowserScreen(browser, onShowDownloads = { tab.intValue = TAB_DOWNLOADS })
                    val enter = fadeIn() + slideInVertically { it / 24 }
                    val exit = fadeOut() + slideOutVertically { it / 24 }
                    AnimatedVisibility(visible = tab.intValue == TAB_DOWNLOADS, enter = enter, exit = exit) {
                        DownloadsScreen(
                            items = downloads.filterNot { it.manual },
                            onPlay = { d -> playing = PlayRequest(listOf(d), 0) }
                        )
                    }
                    AnimatedVisibility(visible = tab.intValue == TAB_LIBRARY, enter = enter, exit = exit) {
                        LibraryScreen(
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

/** Untere Leiste: der gewaehlte Reiter ist eine gelbe Kapsel mit Symbol und Namen. */
@Composable
private fun BottomBar(selected: Int, activeDownloads: Int, onSelect: (Int) -> Unit) {
    Surface(color = Vc.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem("Browser", VcIcons.Globe, selected == TAB_BROWSER, 0, Modifier.weight(1f)) { onSelect(TAB_BROWSER) }
            NavItem("Downloads", VcIcons.Download, selected == TAB_DOWNLOADS, activeDownloads, Modifier.weight(1f)) { onSelect(TAB_DOWNLOADS) }
            NavItem("Mediathek", VcIcons.Library, selected == TAB_LIBRARY, 0, Modifier.weight(1f)) { onSelect(TAB_LIBRARY) }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Vc.Accent else Color.Transparent, label = "nav-bg")
    val fg by animateColorAsState(if (selected) Vc.OnAccent else Vc.Text, label = "nav-fg")
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(20.dp),
        color = bg,
        contentColor = fg
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
                if (badge > 0) {
                    // Zahl der laufenden Downloads
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(start = 18.dp).size(18.dp)
                            .background(if (selected) Vc.OnAccent else Vc.Ok, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$badge", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (selected) Vc.Accent else Color.Black
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
        }
    }
}
