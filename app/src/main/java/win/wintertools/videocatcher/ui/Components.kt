package win.wintertools.videocatcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Eigene Symbole (24x24), die es im Grundsatz der Material-Symbole nicht gibt. */
object VcIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private val black = SolidColor(Color.Black)

    val Download: ImageVector by lazy {
        icon("Download") {
            path(fill = black) {
                moveTo(11f, 4f); horizontalLineTo(13f); verticalLineTo(12.2f)
                lineTo(16.3f, 8.9f); lineTo(17.7f, 10.3f); lineTo(12f, 16f)
                lineTo(6.3f, 10.3f); lineTo(7.7f, 8.9f); lineTo(11f, 12.2f); close()
                moveTo(5f, 18f); horizontalLineTo(19f); verticalLineTo(20f); horizontalLineTo(5f); close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            path(fill = black) {
                moveTo(7f, 5f); horizontalLineTo(10.5f); verticalLineTo(19f); horizontalLineTo(7f); close()
                moveTo(13.5f, 5f); horizontalLineTo(17f); verticalLineTo(19f); horizontalLineTo(13.5f); close()
            }
        }
    }

    /** Weltkugel fuer den Browser */
    val Globe: ImageVector by lazy {
        icon("Globe") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f)
                moveTo(3f, 12f); lineTo(21f, 12f)
                moveTo(12f, 3f); arcTo(4.2f, 9f, 0f, true, true, 12f, 21f); arcTo(4.2f, 9f, 0f, true, true, 12f, 3f)
            }
        }
    }

    /** Bildschirm mit Abspiel-Dreieck fuer die Mediathek */
    val Library: ImageVector by lazy {
        icon("Library") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 5f); horizontalLineTo(19f); arcTo(2f, 2f, 0f, false, true, 21f, 7f)
                verticalLineTo(17f); arcTo(2f, 2f, 0f, false, true, 19f, 19f); horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 17f); verticalLineTo(7f); arcTo(2f, 2f, 0f, false, true, 5f, 5f); close()
            }
            path(fill = black) { moveTo(10f, 8.5f); lineTo(16f, 12f); lineTo(10f, 15.5f); close() }
        }
    }

    private fun ImageVector.Builder.star(filled: Boolean) {
        path(
            fill = if (filled) black else null,
            stroke = black, strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(12f, 3.5f); lineTo(14.5f, 8.8f); lineTo(20.3f, 9.5f); lineTo(16f, 13.5f)
            lineTo(17.2f, 19.3f); lineTo(12f, 16.4f); lineTo(6.8f, 19.3f); lineTo(8f, 13.5f)
            lineTo(3.7f, 9.5f); lineTo(9.5f, 8.8f); close()
        }
    }

    val Star: ImageVector by lazy { icon("Star") { star(true) } }
    val StarOutline: ImageVector by lazy { icon("StarOutline") { star(false) } }

    /** Zwei Pfeile im Kreis: Ausrichtung drehen */
    val Rotate: ImageVector by lazy {
        icon("Rotate") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 12f); arcTo(7f, 7f, 0f, false, false, 6.5f, 7.6f)
                moveTo(6.5f, 4f); verticalLineTo(7.8f); horizontalLineTo(10.3f)
                moveTo(5f, 12f); arcTo(7f, 7f, 0f, false, false, 17.5f, 16.4f)
                moveTo(17.5f, 20f); verticalLineTo(16.2f); horizontalLineTo(13.7f)
            }
        }
    }
}

enum class PillStyle { Primary, Tonal, Outline, Danger }

private fun container(style: PillStyle, enabled: Boolean): Color = when {
    !enabled -> Vc.SurfaceHigh
    style == PillStyle.Primary -> Vc.Accent
    style == PillStyle.Tonal -> Vc.SurfaceTop
    style == PillStyle.Danger -> Vc.ErrSoft
    else -> Color.Transparent
}

private fun content(style: PillStyle, enabled: Boolean): Color = when {
    !enabled -> Vc.Outline
    style == PillStyle.Primary -> Vc.OnAccent
    style == PillStyle.Danger -> Vc.Err
    else -> Vc.Text
}

/** Runder Knopf mit Text, optional mit Symbol davor. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: PillStyle = PillStyle.Primary,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    val fg = content(style, enabled)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(50),
        color = container(style, enabled),
        contentColor = fg,
        border = if (style == PillStyle.Outline) BorderStroke(1.5.dp, if (enabled) Vc.Outline else Vc.Track) else null
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Runder Knopf nur mit Symbol. */
@Composable
fun CircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Tonal,
    enabled: Boolean = true,
    size: Dp = 46.dp
) {
    val fg = content(style, enabled)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = container(style, enabled),
        contentColor = fg,
        border = if (style == PillStyle.Outline) BorderStroke(1.5.dp, if (enabled) Vc.Outline else Vc.Track) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(22.dp))
        }
    }
}

/** Auswahl-Marke: gewaehlt = gelb mit schwarzer Schrift. */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) Vc.Accent else Vc.SurfaceTop,
        contentColor = if (selected) Vc.OnAccent else Vc.Text
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (selected) Vc.OnAccent else Vc.Text)
            if (sub != null) {
                Spacer(Modifier.width(6.dp))
                Text(sub, fontSize = 13.sp, color = if (selected) Vc.OnAccent else Vc.TextDim)
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Vc.Text,
    unfocusedTextColor = Vc.Text,
    focusedContainerColor = Vc.SurfaceHigh,
    unfocusedContainerColor = Vc.SurfaceHigh,
    focusedBorderColor = Vc.Accent,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = Vc.Accent,
    focusedLabelColor = Vc.Accent,
    unfocusedLabelColor = Vc.TextDim,
    focusedLeadingIconColor = Vc.Text,
    unfocusedLeadingIconColor = Vc.TextDim,
    focusedTrailingIconColor = Vc.Text,
    unfocusedTrailingIconColor = Vc.Text
)
