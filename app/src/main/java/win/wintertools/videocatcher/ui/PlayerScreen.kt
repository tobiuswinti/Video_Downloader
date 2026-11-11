package win.wintertools.videocatcher.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import win.wintertools.videocatcher.data.Download
import win.wintertools.videocatcher.data.Downloads

/**
 * Eingebauter Player. Merkt sich die Stelle, an der man aufgehoert hat,
 * und spielt bei Serien die naechste Folge von selbst weiter.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(request: PlayRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var current by remember { mutableStateOf(request.queue[request.index]) }
    var controls by remember { mutableStateOf(true) }
    var landscape by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember {
        // Nur Eintraege, deren Datei erreichbar ist
        val playable = request.queue.mapNotNull { d -> Downloads.uriOf(context, d)?.let { d to it } }
        val startId = request.queue[request.index].id
        val startIndex = playable.indexOfFirst { it.first.id == startId }.coerceAtLeast(0)
        ExoPlayer.Builder(context).build().apply {
            val items = playable.map { (d, uri: Uri) ->
                MediaItem.Builder().setUri(uri).setMediaId(d.id.toString()).build()
            }
            if (items.isEmpty()) {
                error = "Die Datei ist nicht mehr vorhanden."
            } else {
                val startPos = if (request.fromStart) 0L else playable[startIndex].first.positionMs
                setMediaItems(items, startIndex, if (startPos > 0) startPos else C.TIME_UNSET)
                playWhenReady = true
                prepare()
            }
        }
    }

    fun save(final: Boolean) {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        Downloads.savePosition(id, player.currentPosition.coerceAtLeast(0), duration, final)
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull()
                request.queue.firstOrNull { it.id == id }?.let { current = it }
                error = null
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // Beim Wechsel zur naechsten Folge die alte als gesehen vermerken
                if (old.mediaItemIndex != new.mediaItemIndex) {
                    val id = old.mediaItem?.mediaId?.toLongOrNull() ?: return
                    val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                    Downloads.savePosition(id, if (finished) 0L else old.positionMs, 0L, final = true)
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Dieses Video kann der eingebaute Player nicht abspielen."
            }
        }
        player.addListener(listener)
        onDispose {
            save(final = true)
            player.removeListener(listener)
            player.release()
        }
    }

    // Stelle regelmaessig merken, falls die App beendet wird
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (player.isPlaying) save(final = false)
        }
    }

    // Vollbild; Ausrichtung nach Wahl
    DisposableEffect(landscape) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            activity.requestedOrientation =
                if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        onDispose {
            if (window != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    keepScreenOn = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShowNextButton(request.queue.size > 1)
                    setShowPreviousButton(request.queue.size > 1)
                    controllerShowTimeoutMs = 3500
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controls = visibility == android.view.View.VISIBLE
                        }
                    )
                }
            },
            onRelease = { it.player = null }
        )

        if (controls || error != null) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircleButton(Icons.Filled.Close, "Schliessen", onClick = onClose, style = PillStyle.Primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        current.series ?: current.title,
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (current.series != null) {
                        Text(
                            "Staffel ${current.season ?: 1}, Folge ${current.episode ?: 1}",
                            color = Color.White, fontSize = 14.sp, maxLines = 1
                        )
                    }
                }
                CircleButton(VcIcons.Rotate, if (landscape) "Hochkant drehen" else "Quer drehen", onClick = { landscape = !landscape })
            }
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(msg, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                PillButton("Mit anderer App öffnen", onClick = { Downloads.open(context, current) })
            }
        }
    }
}
