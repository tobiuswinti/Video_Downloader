package win.wintertools.videocatcher.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import win.wintertools.videocatcher.browser.Format
import win.wintertools.videocatcher.browser.MediaSniffer
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.Downloads
import win.wintertools.videocatcher.data.Kind
import win.wintertools.videocatcher.data.Status
import win.wintertools.videocatcher.util.PublicStorage

@Composable
fun DownloadsScreen(items: List<Download>, onPlay: (Download) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var link by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Download?>(null) }

    fun addLink() {
        val url = link.trim()
        val hit = MediaSniffer.classify(url, null, knownMedia = true)
        if (hit == null) {
            Toast.makeText(context, "Bitte eine vollständige Adresse eingeben (https://…)", Toast.LENGTH_LONG).show()
            return
        }
        Downloads.start(context, hit.url, hit.kind, hit.ext, title = null, referer = null, userAgent = null, cookie = null)
        link = ""
        focus.clearFocus()
    }

    // Surface faengt alle Beruehrungen ab, damit nichts zum Browser darunter durchgeht
    Surface(modifier = Modifier.fillMaxSize(), color = Vc.Bg, contentColor = Vc.Text) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(Vc.Surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
                val running = items.count { it.active }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Downloads", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Vc.Text, modifier = Modifier.weight(1f))
                    if (running > 0) {
                        val speed = items.filter { it.status == Status.RUNNING }.sumOf { it.speed }
                        Text(
                            "$running aktiv" + if (speed > 0) " · ${Format.bytes(speed)}/s" else "",
                            color = Vc.Ok, fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                        placeholder = { Text("Direkten Video-Link einfügen", color = Vc.TextDim, fontSize = 15.sp, maxLines = 1) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addLink() }),
                        shape = RoundedCornerShape(50),
                        colors = fieldColors()
                    )
                    CircleButton(
                        VcIcons.Download, "Link laden", onClick = { addLink() },
                        style = PillStyle.Primary, enabled = link.isNotBlank(), size = 52.dp
                    )
                }
            }

            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(84.dp).background(Vc.Surface, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(VcIcons.Download, null, tint = Vc.Accent, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Noch keine Downloads", color = Vc.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Im Browser eine Seite öffnen, das Video abspielen und unten auf «Video gefunden» tippen.",
                        color = Vc.TextDim, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 23.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.id }) { d ->
                        DownloadCard(
                            d = d,
                            onPause = { Downloads.pause(context, d.id) },
                            onResume = { Downloads.resume(context, d.id) },
                            onPlay = { onPlay(d) },
                            onShare = { Downloads.share(context, d) },
                            onDelete = { deleting = d }
                        )
                    }
                }
            }
        }
    }

    deleting?.let { d ->
        val done = d.status == Status.DONE
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Vc.SurfaceHigh,
            titleContentColor = Vc.Text,
            textContentColor = Vc.Text,
            title = { Text(if (done) "Download löschen?" else "Download abbrechen?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        if (done) "«${d.title}»\n\nDie Videodatei wird vom Gerät gelöscht und verschwindet aus der Mediathek."
                        else "«${d.title}»\n\nDer bisher geladene Teil wird gelöscht.",
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        if (done) "Löschen" else "Ja, abbrechen",
                        onClick = { Downloads.remove(context, d, deleteFile = true); deleting = null },
                        modifier = Modifier.fillMaxWidth(), style = PillStyle.Danger, icon = Icons.Filled.Delete
                    )
                    Spacer(Modifier.height(8.dp))
                    PillButton("Zurück", onClick = { deleting = null }, modifier = Modifier.fillMaxWidth(), style = PillStyle.Tonal)
                }
            },
            confirmButton = {}
        )
    }
}

/** Kreis links auf der Karte: Fortschritt in Prozent bzw. Zustand. */
@Composable
private fun StatusRing(d: Download) {
    val color = when (d.status) {
        Status.DONE -> Vc.Ok
        Status.ERROR -> Vc.Err
        Status.PAUSED -> Vc.Outline
        else -> Vc.Accent
    }
    val fraction = d.fraction
    val shown by animateFloatAsState(fraction ?: 0f, label = "progress")
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        when {
            d.status == Status.DONE -> {
                Box(Modifier.size(56.dp).background(Vc.Ok, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(30.dp))
                }
            }
            d.status == Status.ERROR -> {
                Box(Modifier.size(56.dp).background(Vc.ErrSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Warning, null, tint = Vc.Err, modifier = Modifier.size(28.dp))
                }
            }
            fraction != null -> {
                CircularProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.size(60.dp),
                    color = color, trackColor = Vc.Track, strokeWidth = 6.dp, strokeCap = StrokeCap.Round
                )
                Text("${(fraction * 100).toInt()}%", color = Vc.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            d.status == Status.RUNNING -> {
                // Groesse unbekannt: laufender Ring
                CircularProgressIndicator(
                    modifier = Modifier.size(60.dp),
                    color = color, trackColor = Vc.Track, strokeWidth = 6.dp, strokeCap = StrokeCap.Round
                )
            }
            else -> {
                Box(Modifier.size(56.dp).background(Vc.SurfaceTop, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(VcIcons.Pause, null, tint = Vc.Text, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    d: Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = Vc.Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusRing(d)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.title, color = Vc.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(statusLine(d), color = Vc.TextDim, fontSize = 14.sp)
                }
            }

            if (d.status == Status.ERROR && d.error != null) {
                Spacer(Modifier.height(10.dp))
                Text(d.error, color = Vc.Err, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            d.note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Vc.TextDim, fontSize = 14.sp)
            }
            if (d.status == Status.DONE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (d.publicUri != null) "Gespeichert in ${PublicStorage.relativePath(d.fileName, d.genre, d.series, d.season)}"
                    else "Gespeichert im App-Ordner",
                    color = Vc.TextDim, fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (d.status) {
                    Status.RUNNING, Status.QUEUED ->
                        PillButton("Pause", onPause, Modifier.weight(1f), icon = VcIcons.Pause, style = PillStyle.Tonal, height = 48.dp)
                    Status.PAUSED ->
                        PillButton("Weiter", onResume, Modifier.weight(1f), icon = Icons.Filled.PlayArrow, height = 48.dp)
                    Status.ERROR ->
                        PillButton("Nochmal", onResume, Modifier.weight(1f), icon = Icons.Filled.Refresh, height = 48.dp)
                    Status.DONE -> {
                        PillButton("Abspielen", onPlay, Modifier.weight(1f), icon = Icons.Filled.PlayArrow, height = 48.dp)
                        CircleButton(Icons.Filled.Share, "Teilen", onShare, size = 48.dp)
                    }
                }
                CircleButton(Icons.Filled.Delete, "Löschen", onDelete, style = PillStyle.Danger, size = 48.dp)
            }
        }
    }
}

private fun statusLine(d: Download): String {
    val size = when {
        d.kind == Kind.HLS && d.segTotal > 0 && d.status != Status.DONE ->
            "Teil ${d.segDone} von ${d.segTotal} · ${Format.bytes(d.done)}"
        d.total > 0 && d.status != Status.DONE -> "${Format.bytes(d.done)} von ${Format.bytes(d.total)}"
        else -> Format.bytes(d.done)
    }
    return when (d.status) {
        Status.RUNNING -> if (d.speed > 0) "$size · ${Format.bytes(d.speed)}/s" else size
        Status.QUEUED -> "Wartet auf freien Platz"
        Status.PAUSED -> "Pausiert · $size"
        Status.DONE -> "Fertig · $size"
        Status.ERROR -> "Fehlgeschlagen · $size"
    }
}
