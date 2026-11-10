package win.wintertools.videocatcher.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun DownloadsScreen(items: List<Download>) {
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
            Column(Modifier.fillMaxWidth().background(Vc.Surface).padding(12.dp)) {
                Text("Downloads", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Vc.Text)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                        placeholder = { Text("Direkten Video-Link einfügen", color = Vc.TextDim, fontSize = 15.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addLink() }),
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
                        onClick = { addLink() },
                        enabled = link.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(52.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Vc.Accent, contentColor = Vc.OnAccent,
                            disabledContainerColor = Vc.Track, disabledContentColor = Vc.TextDim
                        )
                    ) { Text("Laden", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Noch keine Downloads.\n\nIm Browser eine Seite öffnen, das Video abspielen und unten auf den gelben Knopf tippen.",
                        color = Vc.TextDim, fontSize = 17.sp, textAlign = TextAlign.Center, lineHeight = 25.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items, key = { it.id }) { d ->
                        DownloadCard(
                            d = d,
                            onPause = { Downloads.pause(context, d.id) },
                            onResume = { Downloads.resume(context, d.id) },
                            onOpen = { Downloads.open(context, d) },
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
                Text(
                    if (done) "«${d.title}»\n\nSoll auch die gespeicherte Datei gelöscht werden?"
                    else "«${d.title}»\n\nDer bisher geladene Teil wird gelöscht.",
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Downloads.remove(context, d, deleteFile = true)
                    deleting = null
                }) {
                    Text(if (done) "Mit Datei löschen" else "Ja, löschen", color = Vc.Err, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    if (done) {
                        TextButton(onClick = {
                            Downloads.remove(context, d, deleteFile = false)
                            deleting = null
                        }) { Text("Nur aus Liste", color = Vc.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                    TextButton(onClick = { deleting = null }) {
                        Text("Zurück", color = Vc.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }
}

@Composable
private fun DownloadCard(
    d: Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val barColor = when (d.status) {
        Status.DONE -> Vc.Ok
        Status.ERROR -> Vc.Err
        Status.PAUSED -> Vc.Outline
        else -> Vc.Accent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Vc.Surface)
            .border(BorderStroke(2.dp, Vc.Track), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            d.title, color = Vc.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Text(d.fileName, color = Vc.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))

        // Fortschrittsbalken
        val fraction = d.fraction
        if (fraction != null) {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Vc.Track)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(7.dp)).background(barColor))
            }
        } else if (d.status == Status.RUNNING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
                color = Vc.Accent, trackColor = Vc.Track
            )
        } else {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Vc.Track))
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    d.status == Status.DONE -> "Fertig"
                    fraction != null -> "${(fraction * 100).toInt()} %"
                    else -> "–"
                },
                color = barColor, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(12.dp))
            Text(statusLine(d), color = Vc.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }

        if (d.status == Status.ERROR && d.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(d.error, color = Vc.Err, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        d.note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Vc.TextDim, fontSize = 14.sp)
        }
        if (d.status == Status.DONE) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (d.publicUri != null) "Gespeichert in: ${folderOf(d)}/${PublicStorage.FOLDER}"
                else "Gespeichert im App-Ordner",
                color = Vc.TextDim, fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (d.status) {
                Status.RUNNING, Status.QUEUED -> Primary("Pause", Modifier.weight(1f), onPause)
                Status.PAUSED -> Primary("Weiter", Modifier.weight(1f), onResume)
                Status.ERROR -> Primary("Nochmal", Modifier.weight(1f), onResume)
                Status.DONE -> {
                    Primary("Öffnen", Modifier.weight(1f), onOpen)
                    Secondary("Teilen", Modifier.weight(1f), Vc.Text, onShare)
                }
            }
            Secondary("Löschen", Modifier.weight(1f), Vc.Err, onDelete)
        }
    }
}

private fun folderOf(d: Download): String =
    if (PublicStorage.mimeOf(d.fileName).startsWith("audio/")) "Music" else "Movies"

@Composable
private fun Primary(text: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Vc.Accent, contentColor = Vc.OnAccent)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun Secondary(text: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        border = BorderStroke(2.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
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
        Status.DONE -> size
        Status.ERROR -> "Fehlgeschlagen · $size"
    }
}
