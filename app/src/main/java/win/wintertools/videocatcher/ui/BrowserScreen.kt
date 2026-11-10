package win.wintertools.videocatcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import win.wintertools.videocatcher.browser.MediaHit
import win.wintertools.videocatcher.browser.MediaSniffer
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

    fun go() {
        keyboard?.hide()
        focus.clearFocus()
        b.load(b.address)
    }

    Column(Modifier.fillMaxSize().background(Vc.Bg)) {

        // ------------------------------------------------------ Adresszeile
        Row(
            Modifier.fillMaxWidth().background(Vc.Surface).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SquareButton("←", "Zurück", enabled = b.canGoBack) { b.back() }

            OutlinedTextField(
                value = b.address,
                onValueChange = { b.address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                placeholder = { Text("Adresse oder Suche", color = Vc.TextDim, fontSize = 16.sp) },
                trailingIcon = {
                    if (b.address.isNotEmpty()) {
                        Text(
                            "✕", color = Vc.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { b.address = "" }.padding(10.dp)
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }, onDone = { go() }, onSearch = { go() }, onSend = { go() }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Vc.Text,
                    unfocusedTextColor = Vc.Text,
                    focusedContainerColor = Vc.Bg,
                    unfocusedContainerColor = Vc.Bg,
                    focusedBorderColor = Vc.Accent,
                    unfocusedBorderColor = Vc.Outline,
                    cursorColor = Vc.Accent
                )
            )

            Button(
                onClick = { go() },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vc.Accent, contentColor = Vc.OnAccent)
            ) { Text("Los", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

            Box {
                SquareButton("⋮", "Menü") { menuOpen = true }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(Vc.SurfaceHigh)
                ) {
                    MenuItem("↻  Neu laden") { menuOpen = false; b.reload() }
                    MenuItem("→  Vorwärts", enabled = b.canGoForward) { menuOpen = false; b.forward() }
                    MenuItem("⌂  Startseite") { menuOpen = false; b.goHome() }
                    MenuItem("⌕  Seite nach Videos durchsuchen") { menuOpen = false; b.scanPage() }
                    MenuItem(
                        if (b.blockPopups) "☑  Pop-ups blockieren: Ein" else "☐  Pop-ups blockieren: Aus"
                    ) { b.updateBlockPopups(!b.blockPopups) }
                }
            }
        }

        if (b.progress in 1..99) {
            LinearProgressIndicator(
                progress = { b.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Vc.Accent,
                trackColor = Vc.Track
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
        if (b.hits.isNotEmpty()) {
            Button(
                onClick = {
                    b.scanPage()
                    sheetOpen = true
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp).height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vc.Accent, contentColor = Vc.OnAccent)
            ) {
                val n = b.hits.size
                Text(
                    if (n == 1) "↓  1 Video gefunden" else "↓  $n Videos gefunden",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }
        } else if (!b.onStartPage) {
            Text(
                "Noch kein Video erkannt – Video auf der Seite abspielen.",
                color = Vc.TextDim, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().background(Vc.Surface).padding(horizontal = 14.dp, vertical = 10.dp)
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
private fun SquareButton(glyph: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) Vc.Text else Vc.Track
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(2.dp, if (enabled) Vc.Outline else Vc.Track), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (enabled) Vc.Text else Vc.Outline, fontSize = 16.sp) },
        enabled = enabled,
        onClick = onClick
    )
}

/** Gelbe Leiste ueber einem Pop-up: macht klar, dass das nicht die eigentliche Seite ist. */
@Composable
private fun PopupBar(host: String, count: Int, onClose: () -> Unit, onBlock: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Vc.Accent).padding(horizontal = 10.dp, vertical = 8.dp)) {
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
            Button(
                onClick = onClose,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
            ) { Text("✕  Schliessen", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Die Seite dahinter bleibt unverändert.",
                color = Color.Black, fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            Text(
                "Immer blockieren",
                color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(BorderStroke(2.dp, Color.Black), RoundedCornerShape(8.dp))
                    .clickable(onClick = onBlock)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HitSheet(b: BrowserController, onDismiss: () -> Unit, onShowDownloads: () -> Unit) {
    val context = LocalContext.current
    val labels = remember { mutableStateMapOf<String, String>() }
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
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(b.hits, key = { it.key }) { hit ->
                    val ua = b.userAgent
                    val referer = hit.referer ?: b.url.ifBlank { null }
                    LaunchedEffect(hit.url) {
                        val cookie = b.cookieFor(hit.url)
                        labels[hit.key] = withContext(Dispatchers.IO) {
                            MediaSniffer.describe(hit, referer, ua, cookie)
                        }
                    }
                    HitRow(
                        hit = hit,
                        label = labels[hit.key],
                        started = hit.key in started,
                        onDownload = {
                            b.download(hit)
                            started.add(hit.key)
                            Toast.makeText(context, "Download gestartet", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onShowDownloads,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, Vc.Text),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Vc.Text)
            ) { Text("Zu den Downloads", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HitRow(hit: MediaHit, label: String?, started: Boolean, onDownload: () -> Unit) {
    val fallback = if (hit.kind == Kind.HLS) "Stream" else hit.ext.uppercase()
    val address = hit.key.removePrefix("https://").removePrefix("http://")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Vc.Bg)
            .border(BorderStroke(2.dp, Vc.Track), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            Text("✓ Gestartet", color = Vc.Ok, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        } else {
            Button(
                onClick = onDownload,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vc.Accent, contentColor = Vc.OnAccent)
            ) { Text("↓ Laden", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
