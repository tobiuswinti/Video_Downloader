package win.wintertools.videocatcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import win.wintertools.videocatcher.browser.Format
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.Downloads
import win.wintertools.videocatcher.data.Status
import win.wintertools.videocatcher.util.Thumbs

/** Was abgespielt werden soll: eine Liste (z. B. die Folgen einer Serie) und der Einstieg. */
class PlayRequest(val queue: List<Download>, val index: Int, val fromStart: Boolean = false)

/** Zustand der Mediathek, der einen Tab-Wechsel ueberleben soll. */
class LibraryState {
    var query by mutableStateOf("")
    var openSeries by mutableStateOf<String?>(null)
}

private const val NO_GENRE = "Ohne Genre"

val GENRES = listOf(
    "Action", "Abenteuer", "Animation", "Dokumentation", "Drama", "Familie", "Fantasy", "Horror",
    "Komödie", "Krimi", "Musik", "Romantik", "Science-Fiction", "Thriller", "Sonstiges"
)

/** Eine Kachel im Regal: ein Film oder eine ganze Serie. */
private sealed class Tile {
    abstract val key: String
    abstract val title: String
    abstract val genre: String?
    abstract val newest: Long
    abstract val cover: Download

    class Movie(val d: Download) : Tile() {
        override val key get() = "m${d.id}"
        override val title get() = d.title
        override val genre get() = d.genre
        override val newest get() = d.createdAt
        override val cover get() = d
    }

    class Series(val name: String, val episodes: List<Download>) : Tile() {
        override val key get() = "s$name"
        override val title get() = name
        override val genre get() = episodes.firstNotNullOfOrNull { it.genre }
        override val newest get() = episodes.maxOf { it.createdAt }
        override val cover get() = episodes.first()
        val seasons get() = episodes.map { it.season ?: 1 }.distinct().size
    }
}

private fun sortedEpisodes(list: List<Download>): List<Download> =
    list.sortedWith(compareBy<Download> { it.season ?: 1 }.thenBy { it.episode ?: 0 }.thenBy { it.createdAt })

private fun lengthText(ms: Long): String {
    if (ms <= 0) return ""
    val min = ms / 60_000
    return if (min >= 60) "${min / 60} Std. ${min % 60} Min." else "${maxOf(min, 1)} Min."
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format("%d:%02d", s / 60, s % 60)
}

@Composable
fun LibraryScreen(
    all: List<Download>,
    state: LibraryState,
    onPlay: (PlayRequest) -> Unit,
    onAddFiles: () -> Unit
) {
    val context = LocalContext.current
    val items = remember(all) { all.filter { it.status == Status.DONE } }
    val tiles = remember(items) {
        val movies = items.filter { it.series == null }.map { Tile.Movie(it) }
        val series = items.filter { it.series != null }
            .groupBy { it.series!!.lowercase() }
            .map { (_, eps) -> Tile.Series(eps.first().series!!, sortedEpisodes(eps)) }
        movies + series
    }
    var detail by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Download?>(null) }

    fun play(d: Download, fromStart: Boolean = false) {
        if (d.series != null) {
            val eps = sortedEpisodes(items.filter { it.series.equals(d.series, ignoreCase = true) })
            onPlay(PlayRequest(eps, eps.indexOfFirst { it.id == d.id }.coerceAtLeast(0), fromStart))
        } else {
            onPlay(PlayRequest(listOf(d), 0, fromStart))
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Vc.Bg, contentColor = Vc.Text) {
        val seriesName = state.openSeries
        val openTile = tiles.filterIsInstance<Tile.Series>().firstOrNull { it.name.equals(seriesName, ignoreCase = true) }
        if (seriesName != null && openTile != null) {
            SeriesPage(
                tile = openTile,
                onBack = { state.openSeries = null },
                onPlay = { play(it) },
                onMore = { detail = it.id }
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Header(state, onAddFiles)
                when {
                    tiles.isEmpty() -> EmptyLibrary(onAddFiles)
                    state.query.isNotBlank() -> SearchResults(
                        tiles.filter { it.title.contains(state.query.trim(), ignoreCase = true) },
                        onOpen = { open(it, state) { id -> detail = id } }
                    )
                    else -> Shelves(
                        tiles = tiles,
                        items = items,
                        onOpen = { open(it, state) { id -> detail = id } },
                        onResume = { play(it) }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ Dialoge
    val detailItem = items.firstOrNull { it.id == detail }
    if (detailItem != null) {
        DetailSheet(
            d = detailItem,
            onDismiss = { detail = null },
            onPlay = { fromStart -> detail = null; play(detailItem, fromStart) },
            onOpenExternal = { Downloads.open(context, detailItem) },
            onShare = { Downloads.share(context, detailItem) },
            onEdit = { editing = detailItem.id; detail = null },
            onDelete = { deleting = detailItem; detail = null }
        )
    }

    val editItem = items.firstOrNull { it.id == editing }
    if (editItem != null) {
        EditDialog(
            d = editItem,
            knownSeries = tiles.filterIsInstance<Tile.Series>().map { it.name },
            knownGenres = items.mapNotNull { it.genre }.distinct(),
            onDismiss = { editing = null },
            onSave = { title, genre, series, season, episode ->
                Downloads.setMeta(context, editItem.id, title, genre, series, season, episode)
                editing = null
            }
        )
    }

    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Vc.SurfaceHigh,
            titleContentColor = Vc.Text,
            textContentColor = Vc.Text,
            title = { Text(if (d.manual) "Aus der Mediathek entfernen?" else "Löschen?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (d.manual) "«${d.title}»\n\nDie Datei selbst bleibt auf dem Gerät."
                    else "«${d.title}»\n\nDie Videodatei wird vom Gerät gelöscht.",
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                PillButton(
                    if (d.manual) "Entfernen" else "Löschen",
                    onClick = { Downloads.remove(context, d, deleteFile = true); deleting = null },
                    style = PillStyle.Danger, height = 46.dp
                )
            },
            dismissButton = { PillButton("Zurück", onClick = { deleting = null }, style = PillStyle.Tonal, height = 46.dp) }
        )
    }
}

private fun open(tile: Tile, state: LibraryState, showDetail: (Long) -> Unit) {
    when (tile) {
        is Tile.Movie -> showDetail(tile.d.id)
        is Tile.Series -> state.openSeries = tile.name
    }
}

// ------------------------------------------------------------------ Kopfzeile

@Composable
private fun Header(state: LibraryState, onAddFiles: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Vc.Surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Mediathek", color = Vc.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f)
            )
            PillButton("Video", onClick = onAddFiles, icon = Icons.Filled.Add, height = 46.dp)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
            placeholder = { Text("Filme & Serien suchen …", color = Vc.TextDim, fontSize = 16.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(22.dp)) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Suche leeren", tint = Vc.Text,
                        modifier = Modifier.clip(CircleShape).clickable { state.query = "" }.padding(10.dp).size(22.dp)
                    )
                }
            },
            shape = RoundedCornerShape(50),
            colors = fieldColors()
        )
    }
}

@Composable
private fun EmptyLibrary(onAddFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(84.dp).background(Vc.Surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(VcIcons.Library, null, tint = Vc.Accent, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Noch nichts in der Mediathek",
            color = Vc.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Fertige Downloads erscheinen hier von selbst. Videos, die schon auf dem Gerät liegen, lassen sich von Hand hinzufügen.",
            color = Vc.TextDim, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 23.sp
        )
        Spacer(Modifier.height(20.dp))
        PillButton("Video hinzufügen", onClick = onAddFiles, icon = Icons.Filled.Add)
    }
}

// --------------------------------------------------------------------- Regale

@Composable
private fun Shelves(
    tiles: List<Tile>,
    items: List<Download>,
    onOpen: (Tile) -> Unit,
    onResume: (Download) -> Unit
) {
    val resume = remember(items) {
        items.filter { it.positionMs > 10_000 }.sortedByDescending { it.lastPlayedAt }.take(12)
    }
    val newest = remember(tiles) { tiles.sortedByDescending { it.newest }.take(20) }
    val series = remember(tiles) { tiles.filterIsInstance<Tile.Series>().sortedBy { it.name.lowercase() } }
    val byGenre = remember(tiles) {
        tiles.groupBy { it.genre ?: NO_GENRE }
            .toList()
            .sortedWith(compareBy({ it.first == NO_GENRE }, { it.first.lowercase() }))
    }

    // Oben gross: das zuletzt Angefangene, sonst das Neueste
    val heroItem = resume.firstOrNull()
    val heroTile = if (heroItem == null) newest.firstOrNull() else null

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            if (heroItem != null) {
                Hero(
                    cover = heroItem,
                    kicker = "Weiterschauen",
                    title = heroItem.series ?: heroItem.title,
                    sub = (if (heroItem.series != null) "Staffel ${heroItem.season ?: 1}, Folge ${heroItem.episode ?: 1} · " else "") +
                        "bei ${clock(heroItem.positionMs)}",
                    button = "Weiter",
                    onClick = { onResume(heroItem) }
                )
            } else if (heroTile != null) {
                Hero(
                    cover = heroTile.cover,
                    kicker = "Neu",
                    title = heroTile.title,
                    sub = listOfNotNull(heroTile.genre, lengthText(heroTile.cover.durationMs).ifEmpty { null }).joinToString(" · "),
                    button = if (heroTile is Tile.Series) "Zur Serie" else "Ansehen",
                    onClick = { onOpen(heroTile) }
                )
            }
        }
        if (resume.size > 1) {
            item(key = "resume") {
                ShelfTitle("Weiterschauen", null)
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resume.drop(1), key = { it.id }) { d -> ResumeCard(d) { onResume(d) } }
                }
            }
        }
        item(key = "new") {
            ShelfTitle("Neu in der Mediathek", null)
            TileRow(newest, onOpen)
        }
        if (series.isNotEmpty()) {
            item(key = "series") {
                ShelfTitle("Serien", "${series.size}")
                TileRow(series, onOpen)
            }
        }
        items(byGenre, key = { "g" + it.first }) { (genre, list) ->
            ShelfTitle(genre, "${list.size}")
            TileRow(list.sortedBy { it.title.lowercase() }, onOpen)
        }
    }
}

/** Grosses Titelbild mit Verlauf nach unten, darauf Titel und ein Knopf. */
@Composable
private fun Hero(cover: Download, kicker: String, title: String, sub: String, button: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(12.dp).aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(26.dp)).clickable(onClick = onClick)
    ) {
        Cover(cover, title, Modifier.fillMaxSize(), letter = false)
        // Dunkler Verlauf, damit die weisse Schrift auf jedem Bild lesbar bleibt
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x33000000), 0.45f to Color(0x66000000), 1f to Color(0xF2000000))
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(
                kicker.uppercase(), color = Vc.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Text(
                title, color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp
            )
            if (sub.isNotEmpty()) Text(sub, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            PillButton(button, onClick = onClick, icon = Icons.Filled.PlayArrow, height = 48.dp)
        }
    }
}

@Composable
private fun ShelfTitle(title: String, sub: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, color = Vc.Text, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        if (sub != null) {
            Spacer(Modifier.width(10.dp))
            Text(sub, color = Vc.TextDim, fontSize = 15.sp)
        }
    }
}

@Composable
private fun TileRow(tiles: List<Tile>, onOpen: (Tile) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tiles, key = { it.key }) { tile -> PosterCard(tile, 132.dp) { onOpen(tile) } }
    }
}

@Composable
private fun SearchResults(tiles: List<Tile>, onOpen: (Tile) -> Unit) {
    if (tiles.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text("Nichts gefunden.", color = Vc.TextDim, fontSize = 17.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(tiles, key = { it.key }) { tile -> PosterCard(tile, null) { onOpen(tile) } }
    }
}

@Composable
private fun thumbOf(d: Download): ImageBitmap? {
    val context = LocalContext.current
    val bmp by produceState(initialValue = Thumbs.cached(d.id), d.id) {
        if (value == null) value = Thumbs.load(context, d)
    }
    return bmp
}

private val coverTints = listOf(
    Color(0xFF6B5210), Color(0xFF14532D), Color(0xFF1E3A5F), Color(0xFF6B2E1A), Color(0xFF4A2A6B), Color(0xFF0F4F4A)
)

/** Bild aus dem Video; ohne Bild eine farbige Flaeche mit dem Anfangsbuchstaben. */
@Composable
private fun Cover(d: Download, title: String, modifier: Modifier, letter: Boolean = true) {
    val bmp = thumbOf(d)
    val tint = coverTints[title.sumOf { it.code } % coverTints.size]
    Box(
        modifier.background(Brush.linearGradient(listOf(tint, Vc.SurfaceHigh))),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (letter) {
            Text(
                title.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White, fontSize = 54.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProgressLine(d: Download, modifier: Modifier) {
    if (d.positionMs > 10_000 && d.durationMs > 0) {
        val f = (d.positionMs.toFloat() / d.durationMs).coerceIn(0f, 1f)
        Box(modifier.fillMaxWidth().height(5.dp).background(Vc.Track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Vc.Accent))
        }
    }
}

/** Hochformat-Kachel wie ein Filmplakat, darunter Titel und eine Zeile Angaben. */
@Composable
private fun PosterCard(tile: Tile, width: Dp?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clip(shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape)) {
            Cover(tile.cover, tile.title, Modifier.fillMaxSize())
            if (tile is Tile.Series) {
                Text(
                    "Serie", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(50))
                        .background(Vc.Accent).padding(horizontal = 10.dp, vertical = 3.dp)
                )
            } else if (tile is Tile.Movie) {
                ProgressLine(tile.d, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tile.title, color = Vc.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp
        )
        val sub = when (tile) {
            is Tile.Movie -> lengthText(tile.d.durationMs)
            is Tile.Series -> {
                val n = tile.episodes.size
                val s = tile.seasons
                (if (s > 1) "$s Staffeln · " else "") + (if (n == 1) "1 Folge" else "$n Folgen")
            }
        }
        if (sub.isNotEmpty()) Text(sub, color = Vc.TextDim, fontSize = 13.sp, maxLines = 1)
    }
}

/** Breite Kachel fuer "Weiterschauen" mit Fortschritt. */
@Composable
private fun ResumeCard(d: Download, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(240.dp).clip(shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)) {
            Cover(d, d.title, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.Center).size(52.dp).background(Vc.Accent, CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(30.dp)) }
            ProgressLine(d, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (d.series != null) "${d.series} · S${d.season ?: 1} F${d.episode ?: 1}" else d.title,
            color = Vc.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            "Weiter bei ${clock(d.positionMs)}" + if (d.durationMs > 0) " von ${clock(d.durationMs)}" else "",
            color = Vc.TextDim, fontSize = 13.sp, maxLines = 1
        )
    }
}

// ---------------------------------------------------------------- Serienseite

@Composable
private fun SeriesPage(tile: Tile.Series, onBack: () -> Unit, onPlay: (Download) -> Unit, onMore: (Download) -> Unit) {
    val seasons = remember(tile.episodes) { tile.episodes.map { it.season ?: 1 }.distinct().sorted() }
    var season by remember(tile.name) { mutableIntStateOf(seasons.first()) }
    if (season !in seasons) season = seasons.first()
    val episodes = tile.episodes.filter { (it.season ?: 1) == season }
    val next = tile.episodes.filter { it.positionMs > 10_000 }.maxByOrNull { it.lastPlayedAt } ?: tile.episodes.first()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Vc.Surface).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Zurück zur Mediathek", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tile.name, color = Vc.Text, fontSize = 22.sp, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    val n = tile.episodes.size
                    Text(
                        listOfNotNull(tile.genre, if (n == 1) "1 Folge" else "$n Folgen").joinToString(" · "),
                        color = Vc.TextDim, fontSize = 14.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            PillButton(
                (if (next.positionMs > 10_000) "Weiter: " else "") + "Staffel ${next.season ?: 1}, Folge ${next.episode ?: 1}",
                onClick = { onPlay(next) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.PlayArrow
            )
            if (seasons.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    seasons.forEach { s -> Chip("Staffel $s", selected = s == season) { season = s } }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (seasons.size == 1) {
                item { Text("Staffel $season", color = Vc.Text, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) }
            }
            items(episodes, key = { it.id }) { d -> EpisodeRow(d, onPlay = { onPlay(d) }, onMore = { onMore(d) }) }
        }
    }
}

@Composable
private fun EpisodeRow(d: Download, onPlay: () -> Unit, onMore: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Vc.Surface)
            .clickable(onClick = onPlay).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            Cover(d, d.title, Modifier.fillMaxSize())
            ProgressLine(d, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Folge ${d.episode ?: 1}", color = Vc.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(d.title, color = Vc.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val len = lengthText(d.durationMs)
            if (len.isNotEmpty()) Text(len, color = Vc.TextDim, fontSize = 13.sp)
        }
        CircleButton(Icons.Filled.MoreVert, "Mehr", onClick = onMore, size = 44.dp)
    }
}

// -------------------------------------------------------------------- Details

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(
    d: Download,
    onDismiss: () -> Unit,
    onPlay: (fromStart: Boolean) -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Vc.Surface,
        contentColor = Vc.Text
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding()
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp))) {
                Cover(d, d.title, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(12.dp))
            Text(d.title, color = Vc.Text, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            val meta = listOfNotNull(
                if (d.series != null) "${d.series} · Staffel ${d.season ?: 1}, Folge ${d.episode ?: 1}" else null,
                d.genre ?: NO_GENRE,
                lengthText(d.durationMs).ifEmpty { null },
                if (d.done > 0) Format.bytes(d.done) else null
            ).joinToString(" · ")
            Text(meta, color = Vc.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))

            val resume = d.positionMs > 10_000
            val wide = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            PillButton(
                if (resume) "Weiter bei ${clock(d.positionMs)}" else "Abspielen",
                onClick = { onPlay(false) }, modifier = wide, icon = Icons.Filled.PlayArrow
            )
            if (resume) PillButton("Von vorn abspielen", onClick = { onPlay(true) }, modifier = wide, style = PillStyle.Tonal, icon = Icons.Filled.Refresh)
            PillButton("Genre / Serie bearbeiten", onClick = onEdit, modifier = wide, style = PillStyle.Tonal, icon = Icons.Filled.Edit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Andere App", onClick = onOpenExternal, modifier = Modifier.weight(1f).padding(bottom = 8.dp), style = PillStyle.Tonal)
                PillButton("Teilen", onClick = onShare, modifier = Modifier.weight(1f).padding(bottom = 8.dp), style = PillStyle.Tonal, icon = Icons.Filled.Share)
            }
            PillButton(
                if (d.manual) "Aus Mediathek entfernen" else "Löschen",
                onClick = onDelete, modifier = wide, style = PillStyle.Danger, icon = Icons.Filled.Delete
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ----------------------------------------------------------------- Bearbeiten

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    d: Download,
    knownSeries: List<String>,
    knownGenres: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, genre: String?, series: String?, season: Int?, episode: Int?) -> Unit
) {
    var title by remember { mutableStateOf(d.title) }
    var isSeries by remember { mutableStateOf(d.series != null) }
    var genre by remember { mutableStateOf(d.genre.orEmpty()) }
    var series by remember { mutableStateOf(d.series.orEmpty()) }
    var season by remember { mutableStateOf((d.season ?: 1).toString()) }
    var episode by remember { mutableStateOf((d.episode ?: 1).toString()) }
    val genres = remember(knownGenres) { (GENRES + knownGenres).distinct() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp),
            shape = RoundedCornerShape(28.dp),
            color = Vc.Surface,
            contentColor = Vc.Text
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Einordnen", fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("Titel") },
                    modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                    shape = RoundedCornerShape(16.dp), colors = fieldColors()
                )
                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Film", selected = !isSeries) { isSeries = false }
                    Chip("Serie", selected = isSeries) { isSeries = true }
                }

                if (isSeries) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = series, onValueChange = { series = it }, label = { Text("Name der Serie") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                        shape = RoundedCornerShape(16.dp), colors = fieldColors()
                    )
                    val suggestions = knownSeries.filter { !it.equals(series, ignoreCase = true) }
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.take(8).forEach { name -> Chip(name, selected = false) { series = name } }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = season, onValueChange = { v -> season = v.filter { it.isDigit() }.take(2) },
                            label = { Text("Staffel") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                            shape = RoundedCornerShape(16.dp), colors = fieldColors()
                        )
                        OutlinedTextField(
                            value = episode, onValueChange = { v -> episode = v.filter { it.isDigit() }.take(3) },
                            label = { Text("Folge") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                            shape = RoundedCornerShape(16.dp), colors = fieldColors()
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    if (isSeries) "Genre (gilt für die ganze Serie)" else "Genre",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.forEach { g ->
                        Chip(g, selected = g.equals(genre, ignoreCase = true)) { genre = if (g.equals(genre, ignoreCase = true)) "" else g }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = genre, onValueChange = { genre = it }, label = { Text("oder eigenes Genre") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 16.sp, color = Vc.Text),
                    shape = RoundedCornerShape(16.dp), colors = fieldColors()
                )

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Abbrechen", onClick = onDismiss, modifier = Modifier.weight(1f), style = PillStyle.Tonal)
                    PillButton(
                        "Speichern",
                        onClick = {
                            val name = if (isSeries) series.trim().ifEmpty { null } else null
                            onSave(title, genre, name, season.toIntOrNull(), episode.toIntOrNull())
                        },
                        modifier = Modifier.weight(1f), icon = Icons.Filled.Check
                    )
                }
            }
        }
    }
}
