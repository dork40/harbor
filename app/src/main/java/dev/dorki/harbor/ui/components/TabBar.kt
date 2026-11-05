package dev.dorki.harbor.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.dorki.harbor.ui.theme.Palette

/** Height of the bottom tab bar (including the gesture inset), so scrolling pages can leave room for it. */
val LocalBarInset = compositionLocalOf { 0.dp }

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.GridView),
    SERVERS("Servers", Icons.Rounded.Dns),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

val TabBarHeight = 58.dp

@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, haze: HazeState, modifier: Modifier = Modifier) {
    val glass = Palette.glass && Build.VERSION.SDK_INT >= 31
    val shape = Modifier
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (glass) Modifier.hazeChild(
                    haze,
                    style = HazeStyle(
                        backgroundColor = Palette.Black,
                        tint = HazeTint(Palette.Black.copy(alpha = Palette.glassAlpha * 0.8f)),
                        blurRadius = Palette.blurRadius,
                        noiseFactor = 0f,
                    ),
                ) else Modifier.background(Palette.Black)
            )
            .drawBehind { drawLine(Palette.Stroke, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
    ) {
        Row(Modifier.fillMaxWidth().height(TabBarHeight)) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .height(TabBarHeight)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, tab.label, tint = if (on) Palette.Text else Palette.Faint, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.0.sp),
                        color = if (on) Palette.Text else Palette.Faint,
                        fontFamily = if (Palette.black) FontFamily.Monospace else null,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier.width(if (on) 16.dp else 0.dp).height(2.dp)
                            .background(if (on) Palette.Accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(1.dp))
                    )
                }
            }
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).fillMaxWidth())
    }
}



/**
 * Drop this inside a dialog or bottom sheet to blur the app behind it when black mode's glass is on.
 * (Needs Android 12+ and a device that allows window blur; otherwise it quietly does nothing.)
 */
@Composable
fun GlassBehind() {
    if (!Palette.glass || Build.VERSION.SDK_INT < 31) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val px = with(LocalDensity.current) { Palette.blurRadius.roundToPx() }
    SideEffect {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.also { it.blurBehindRadius = px }
    }
}
