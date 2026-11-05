package dev.dorki.harbor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.dorki.harbor.ui.theme.Palette

/** Status marker: a solid core with a quiet ring that pulses outward while [pulsing]. */
@Composable
fun StatusOrb(color: Color, icon: ImageVector, pulsing: Boolean, modifier: Modifier = Modifier, size: Dp = 60.dp) {
    val c by animateColorAsState(color, tween(600), label = "orb")
    val t = rememberInfiniteTransition(label = "pulse")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (pulsing) 1300 else 3200, easing = LinearEasing)), label = "wave")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            drawCircle(c.copy(alpha = (1f - wave) * if (pulsing) 0.5f else 0.18f), radius = r * (0.62f + 0.38f * wave), style = Stroke(1.5.dp.toPx()))
            drawCircle(c.copy(alpha = 0.14f), r * 0.62f)
            drawCircle(c, r * 0.46f)
        }
        Icon(icon, null, tint = Palette.Black, modifier = Modifier.size(size * 0.28f))
    }
}

@Composable
fun Ring(fraction: Float?, color: Color, modifier: Modifier = Modifier, stroke: Dp = 6.dp, content: @Composable () -> Unit) {
    val f by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(700), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = stroke.toPx()
            val inset = s / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - s, size.height - s)
            drawArc(Palette.Stroke, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
            if (fraction != null) {
                drawArc(color, 135f, 270f * f, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "bar")
    Box(modifier.height(6.dp).fillMaxWidth().clip(CircleShape).background(Palette.Stroke)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (filled) 0.45f else 0.25f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = if (filled) color else Palette.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Tries each icon URL in turn, falling back to a monogram. */
@Composable
fun AppIcon(urls: List<String>, name: String, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    var index by remember(urls) { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(size * 0.28f * (Palette.radius / 22f))
    Box(
        modifier.size(size).clip(shape).background(Palette.Surface3),
        contentAlignment = Alignment.Center,
    ) {
        val url = urls.getOrNull(index)
        var loaded by remember(urls) { mutableStateOf(false) }
        if (!loaded) Monogram(name, size)
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(size * 0.14f).size(size),
                onSuccess = { loaded = true },
                onError = { index++ },
            )
        }
    }
}

private val monoColors = listOf(Color(0xFF5CC8A1), Color(0xFFA08CF0), Color(0xFFE5B452), Color(0xFF5EA8F0), Color(0xFFF0865C))

@Composable
private fun Monogram(name: String, size: Dp) {
    val c = monoColors[(name.hashCode() and 0x7fffffff) % monoColors.size]
    Box(
        Modifier.size(size).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(), color = c, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp, maxLines = 1, overflow = TextOverflow.Clip,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            fontFamily = if (Palette.black) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, tint: Color? = null, content: @Composable () -> Unit) {
    val shape = Palette.shape(22.dp)
    val fill = if (Palette.glass) Palette.Surface2.copy(alpha = Palette.glassAlpha * 0.6f) else Palette.Surface
    Column(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, tint?.copy(alpha = 0.45f) ?: Palette.Stroke, shape)
    ) { content() }
}

fun formatBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100 || i == 0) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

fun formatUptime(sec: Long): String {
    val d = sec / 86_400
    val h = (sec % 86_400) / 3_600
    val m = (sec % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}
