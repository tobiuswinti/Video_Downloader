package win.wintertools.videocatcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import win.wintertools.videocatcher.browser.BrowserController
import win.wintertools.videocatcher.browser.Format
import win.wintertools.videocatcher.browser.HitInfo
import win.wintertools.videocatcher.browser.MediaHit
import win.wintertools.videocatcher.browser.MediaSniffer
import win.wintertools.videocatcher.browser.Quality
import win.wintertools.videocatcher.data.Kind

/** Eine vorhandene Ansicht fuer Compose bereitstellen (vorher aus altem Elternteil loesen). */
private fun <T : View> detached(view: T): T {
    (view.parent as? ViewGroup)?.removeView(view)
    return view
}

@Composable
fun BrowserScreen(b: BrowserController, onShowDownloads: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }

    fun go(target: String = b.address) {
        keyboard?.hide()
        focus.clearFocus()
        b.load(target)
    }

    Column(Modifier.fillMaxSize().background(Vc.Bg)) {

        // ------------------------------------------------------ Adresszeile
        Row(
            Modifier.fillMaxWidth().background(Vc.Surface).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", onClick = { b.back() }, enabled = b.canGoBack)

            OutlinedTextField(
                value = b.address,
                onValueChange = { b.address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                placeholder = { Text("Adresse oder Suche", color = Vc.TextDim, fontSize = 16.sp, maxLines = 1) },
                trailingIcon = {
                    // Seite offen: Stern zum Merken. Sonst: Eingabe loeschen.
                    if (!b.onStartPage && b.address == b.url) {
                        Icon(
                            if (b.isBookmarked) VcIcons.Star else VcIcons.StarOutline,
                            contentDescription = if (b.isBookmarked) "Aus dem Schnellzugriff entfernen" else "Zum Schnellzugriff hinzufügen",
                            tint = if (b.isBookmarked) Vc.Accent else Vc.Text,
                            modifier = Modifier.clip(CircleShape).clickable { b.toggleBookmark() }.padding(10.dp).size(24.dp)
                        )
                    } else if (b.address.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Eingabe löschen", tint = Vc.Text,
                            modifier = Modifier.clip(CircleShape).clickable { b.address = "" }.padding(10.dp).size(22.dp)
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }, onDone = { go() }, onSearch = { go() }, onSend = { go() }),
                shape = RoundedCornerShape(50),
                colors = fieldColors()
            )

            CircleButton(Icons.AutoMirrored.Filled.ArrowForward, "Seite öffnen", onClick = { go() }, style = PillStyle.Primary, size = 52.dp)

            Box {
                CircleButton(Icons.Filled.MoreVert, "Menü", onClick = { menuOpen = true })
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(Vc.SurfaceHigh)
                ) {
                    MenuItem(Icons.Filled.Refresh, "Neu laden") { menuOpen = false; b.reload() }
                    MenuItem(Icons.AutoMirrored.Filled.ArrowForward, "Vorwärts", enabled = b.canGoForward) { menuOpen = false; b.forward() }
                    MenuItem(Icons.Filled.Home, "Startseite") { menuOpen = false; b.goHome() }
                    MenuItem(Icons.Filled.Search, "Seite nach Videos durchsuchen") { menuOpen = false; b.scanPage() }
                    MenuItem(
                        if (b.blockPopups) Icons.Filled.Check else Icons.Filled.Close,
                        if (b.blockPopups) "Pop-ups blockieren: Ein" else "Pop-ups blockieren: Aus"
                    ) { b.updateBlockPopups(!b.blockPopups) }
                }
            }
        }

        if (b.progress in 1..99) {
            LinearProgressIndicator(
                progress = { b.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Vc.Accent,
                trackColor = Vc.Surface
            )
        }

        b.error?.let { msg ->
            Text(
                "Seite konnte nicht geladen werden: $msg",
                color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().background(Vc.Err).padding(12.dp)
            )
        }

        // ------------------------------------------------- Seite und Pop-up
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { detached(b.main) })

            if (b.onStartPage) StartPage(b, onOpen = { go(it) })

            val popup = b.popups.lastOrNull()
            if (popup != null) {
                Column(Modifier.fillMaxSize().background(Vc.Bg)) {
                    PopupBar(
                        host = b.popupHost,
                        count = b.popups.size,
                        onClose = { b.closeTopPopup() },
                        onBlock = { b.updateBlockPopups(true) }
                    )
                    key(popup) {
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { detached(popup) })
                    }
                }
            }
        }

        // ----------------------------------------------------- Video-Leiste
        AnimatedVisibility(
            visible = b.hits.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val n = b.hits.size
            PillButton(
                text = if (n == 1) "1 Video gefunden" else "$n Videos gefunden",
                icon = VcIcons.Download,
                onClick = {
                    b.scanPage()
                    sheetOpen = true
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                height = 56.dp
            )
        }
        if (b.hits.isEmpty() && !b.onStartPage) {
            Text(
                "Noch kein Video erkannt – Video auf der Seite abspielen.",
                color = Vc.TextDim, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().background(Vc.Surface).padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }

    if (sheetOpen) {
        HitSheet(b, onDismiss = { sheetOpen = false }, onShowDownloads = {
            sheetOpen = false
            onShowDownloads()
        })
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) Vc.Text else Vc.Outline
    DropdownMenuItem(
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp)) },
        text = { Text(text, color = color, fontSize = 16.sp) },
        enabled = enabled,
        onClick = onClick
    )
}

// ------------------------------------------------------------------ Startseite

private val tileColors = listOf(
    Color(0xFFFFD23F), Color(0xFF4ADE80), Color(0xFF7CC4FF), Color(0xFFFF9F7A), Color(0xFFD5A6FF), Color(0xFF6EE7D8)
)

/** Startseite: Schnellzugriff auf gemerkte Seiten und eine kurze Anleitung. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StartPage(b: BrowserController, onOpen: (String) -> Unit) {
    var removing by remember { mutableStateOf<BrowserController.Bookmark?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().background(Vc.Bg),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                Text("VideoCatcher", color = Vc.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Videos von Webseiten speichern", color = Vc.TextDim, fontSize = 16.sp)
                Spacer(Modifier.height(18.dp))
                Text("Schnellzugriff", color = Vc.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (b.bookmarks.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = Vc.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(VcIcons.StarOutline, null, tint = Vc.Accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Noch keine Seiten gemerkt. Eine Seite öffnen und oben in der Adresszeile den Stern antippen.",
                            color = Vc.TextDim, fontSize = 15.sp, lineHeight = 21.sp
                        )
                    }
                }
            }
        } else {
            items(b.bookmarks, key = { it.url }) { bm ->
                val host = b.hostOf(bm.url)
                val color = tileColors[host.sumOf { it.code } % tileColors.size]
                Surface(
                    color = Vc.Surface,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .combinedClickable(onClick = { onOpen(bm.url) }, onLongClick = { removing = bm })
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Box(
                            Modifier.size(44.dp).background(color, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                host.take(1).uppercase().ifEmpty { "?" },
                                color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            bm.title, color = Vc.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp
                        )
                        Text(host, color = Vc.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 14.dp)) {
                Text("So geht's", color = Vc.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Surface(color = Vc.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Step(1, "Oben die Adresse eingeben und den gelben Pfeil antippen.")
                        Step(2, "Das Video auf der Seite abspielen.")
                        Step(3, "Unten erscheint «Video gefunden» – antippen, Qualität wählen, laden.")
                        Step(4, "Fertige Videos stehen in der Mediathek.")
                    }
                }
                if (b.bookmarks.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Tipp: Kachel lange drücken, um sie zu entfernen.", color = Vc.TextDim, fontSize = 14.sp)
                }
            }
        }
    }

    removing?.let { bm ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Vc.SurfaceHigh,
            titleContentColor = Vc.Text,
            textContentColor = Vc.Text,
            title = { Text("Aus dem Schnellzugriff entfernen?", fontWeight = FontWeight.Bold) },
            text = { Text(bm.title, fontSize = 16.sp) },
            confirmButton = {
                PillButton("Entfernen", onClick = { b.removeBookmark(bm); removing = null }, style = PillStyle.Danger, height = 46.dp)
            },
            dismissButton = {
                PillButton("Zurück", onClick = { removing = null }, style = PillStyle.Tonal, height = 46.dp)
            }
        )
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Vc.Accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = Vc.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Vc.Text, fontSize = 16.sp, lineHeight = 22.sp)
    }
}

// --------------------------------------------------------------------- Pop-up

/** Gelbe Leiste ueber einem Pop-up: macht klar, dass das nicht die eigentliche Seite ist. */
@Composable
private fun PopupBar(host: String, count: Int, onClose: () -> Unit, onBlock: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Vc.Accent).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (count > 1) "Pop-up ($count offen)" else "Pop-up",
                    color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    host.ifBlank { "wird geladen…" },
                    color = Color.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Surface(onClick = onClose, shape = RoundedCornerShape(50), color = Color.Black, contentColor = Color.White) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Schliessen", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Die Seite dahinter bleibt unverändert.",
                color = Color.Black, fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            Surface(onClick = onBlock, shape = RoundedCornerShape(50), color = Color(0x26000000), contentColor = Color.Black) {
                Text(
                    "Immer blockieren", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------ Gefundene Videos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HitSheet(b: BrowserController, onDismiss: () -> Unit, onShowDownloads: () -> Unit) {
    val context = LocalContext.current
    val infos = remember { mutableStateMapOf<String, HitInfo>() }
    val chosen = remember { mutableStateMapOf<String, Int>() }
    val started = remember { mutableStateListOf<String>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Vc.Surface,
        contentColor = Vc.Text
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Gefundene Videos", color = Vc.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (b.hits.size > 1) {
                Text(
                    "Bei mehreren Einträgen ist meist der grösste oder längste der richtige.",
                    color = Vc.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(b.hits, key = { it.key }) { hit ->
                    val ua = b.userAgent
                    val referer = hit.referer ?: b.url.ifBlank { null }
                    LaunchedEffect(hit.url) {
                        val cookie = b.cookieFor(hit.url)
                        val info = withContext(Dispatchers.IO) { MediaSniffer.inspect(hit, referer, ua, cookie) }
                        infos[hit.key] = info
                        // Zuletzt gewaehlte Aufloesung vorwaehlen, sonst die beste
                        if (info.qualities.isNotEmpty() && hit.key !in chosen) {
                            val want = b.preferredHeight
                            val index = if (want > 0) {
                                info.qualities.indices.minByOrNull { kotlin.math.abs(info.qualities[it].height - want) } ?: 0
                            } else 0
                            chosen[hit.key] = index
                        }
                    }
                    val info = infos[hit.key]
                    val qualities = info?.qualities.orEmpty()
                    HitRow(
                        hit = hit,
                        label = info?.label,
                        qualities = qualities,
                        selected = chosen[hit.key] ?: 0,
                        onSelect = { chosen[hit.key] = it },
                        started = hit.key in started,
                        onDownload = {
                            b.download(hit, qualities.getOrNull(chosen[hit.key] ?: 0))
                            started.add(hit.key)
                            Toast.makeText(context, "Download gestartet", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            PillButton("Zu den Downloads", onClick = onShowDownloads, modifier = Modifier.fillMaxWidth(), style = PillStyle.Tonal)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HitRow(
    hit: MediaHit,
    label: String?,
    qualities: List<Quality>,
    selected: Int,
    onSelect: (Int) -> Unit,
    started: Boolean,
    onDownload: () -> Unit
) {
    val fallback = if (hit.kind == Kind.HLS) "Stream" else hit.ext.uppercase()
    val address = hit.key.removePrefix("https://").removePrefix("http://")
    Surface(color = Vc.SurfaceHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label ?: "$fallback · wird geprüft…",
                        color = Vc.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        address, color = Vc.TextDim, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (started) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = Vc.Ok, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Gestartet", color = Vc.Ok, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    PillButton("Laden", onClick = onDownload, icon = VcIcons.Download, height = 48.dp)
                }
            }
            // Qualitaet waehlen: kleinere Stufe = weniger Speicher
            if (qualities.isNotEmpty() && !started) {
                Spacer(Modifier.height(12.dp))
                Text("Qualität", color = Vc.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    qualities.forEachIndexed { i, q ->
                        Chip(
                            text = q.label,
                            sub = q.sizeBytes?.let { "~" + Format.bytes(it) },
                            selected = i == selected,
                            onClick = { onSelect(i) }
                        )
                    }
                }
            }
        }
    }
}
