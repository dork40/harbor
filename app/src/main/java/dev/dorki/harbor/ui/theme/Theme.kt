package dev.dorki.harbor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A complete set of colors. [accent] is for chrome (buttons, switches, links); [lan] and [tailscale]
 * are data colors for graphs and the two network routes; [good]/[warn]/[bad] are status.
 */
class Scheme(
    val id: String,
    val label: String,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val stroke: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val lan: Color,
    val tailscale: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
)

object Schemes {
    val Harbor = Scheme(
        "harbor", "Harbor",
        bg = Color(0xFF0A0C0F), surface = Color(0xFF111418), surface2 = Color(0xFF171B20), surface3 = Color(0xFF1F242B),
        stroke = Color(0xFF272D35), text = Color(0xFFE7EAEE), muted = Color(0xFF8A929E), faint = Color(0xFF535B67),
        accent = Color(0xFF8DB4D8), onAccent = Color(0xFF0A0C0F),
        lan = Color(0xFF5CC8A1), tailscale = Color(0xFFA08CF0),
        good = Color(0xFF5CC8A1), warn = Color(0xFFE5B452), bad = Color(0xFFEB6B7A),
    )
    val Gruvbox = Scheme(
        "gruvbox", "Gruvbox",
        bg = Color(0xFF181A1B), surface = Color(0xFF1D2021), surface2 = Color(0xFF282828), surface3 = Color(0xFF32302F),
        stroke = Color(0xFF3C3836), text = Color(0xFFEBDBB2), muted = Color(0xFFA89984), faint = Color(0xFF665C54),
        accent = Color(0xFFD79921), onAccent = Color(0xFF1D2021),
        lan = Color(0xFFB8BB26), tailscale = Color(0xFF83A598),
        good = Color(0xFFB8BB26), warn = Color(0xFFFABD2F), bad = Color(0xFFFB4934),
    )
    val Nord = Scheme(
        "nord", "Nord",
        bg = Color(0xFF20252E), surface = Color(0xFF272D38), surface2 = Color(0xFF2E3440), surface3 = Color(0xFF3B4252),
        stroke = Color(0xFF434C5E), text = Color(0xFFECEFF4), muted = Color(0xFF9AA5B8), faint = Color(0xFF616E88),
        accent = Color(0xFF88C0D0), onAccent = Color(0xFF20252E),
        lan = Color(0xFFA3BE8C), tailscale = Color(0xFFB48EAD),
        good = Color(0xFFA3BE8C), warn = Color(0xFFEBCB8B), bad = Color(0xFFBF616A),
    )
    val RosePine = Scheme(
        "rosepine", "Rosé Pine",
        bg = Color(0xFF14121F), surface = Color(0xFF191724), surface2 = Color(0xFF1F1D2E), surface3 = Color(0xFF26233A),
        stroke = Color(0xFF332F4A), text = Color(0xFFE0DEF4), muted = Color(0xFF908CAA), faint = Color(0xFF6E6A86),
        accent = Color(0xFFC4A7E7), onAccent = Color(0xFF14121F),
        lan = Color(0xFF9CCFD8), tailscale = Color(0xFFC4A7E7),
        good = Color(0xFF9CCFD8), warn = Color(0xFFF6C177), bad = Color(0xFFEB6F92),
    )
    val Catppuccin = Scheme(
        "catppuccin", "Catppuccin",
        bg = Color(0xFF0F0F17), surface = Color(0xFF181825), surface2 = Color(0xFF1E1E2E), surface3 = Color(0xFF313244),
        stroke = Color(0xFF3B3C50), text = Color(0xFFCDD6F4), muted = Color(0xFF9399B2), faint = Color(0xFF6C7086),
        accent = Color(0xFF89B4FA), onAccent = Color(0xFF11111B),
        lan = Color(0xFFA6E3A1), tailscale = Color(0xFFCBA6F7),
        good = Color(0xFFA6E3A1), warn = Color(0xFFF9E2AF), bad = Color(0xFFF38BA8),
    )

    /** Black mode: neutral greys only; color is kept for status, graphs and app icons. */
    val Mono = Scheme(
        "mono", "Black",
        bg = Color(0xFF000000), surface = Color(0xFF0B0B0B), surface2 = Color(0xFF111111), surface3 = Color(0xFF1A1A1A),
        stroke = Color(0xFF2A2A2A), text = Color(0xFFF2F2F2), muted = Color(0xFF8F8F8F), faint = Color(0xFF555555),
        accent = Color(0xFFF2F2F2), onAccent = Color(0xFF000000),
        lan = Color(0xFF7FD6A8), tailscale = Color(0xFF8FA8FF),
        good = Color(0xFF7FD6A8), warn = Color(0xFFE8BC66), bad = Color(0xFFF0707E),
    )

    val choices = listOf(Harbor, Catppuccin, Nord, RosePine, Gruvbox)
    fun byId(id: String?) = choices.firstOrNull { it.id == id } ?: Harbor
}

/** What the user picked in Settings → Appearance. */
data class Appearance(
    val scheme: String = Schemes.Harbor.id,
    /** Monochrome "black mode": greys, glass panels, tighter corners. */
    val black: Boolean = false,
    /** 0..1, how strongly glass surfaces blur what is behind them (black mode only). */
    val blur: Float = 0.7f,
    /** Corner radius for large surfaces, in dp. */
    val radius: Int = 22,
    val blackRadius: Int = 12,
)

object Palette {
    var scheme by mutableStateOf(Schemes.Harbor)
    var black by mutableStateOf(false)
    var blur by mutableStateOf(0.7f)
    var radius by mutableStateOf(22)

    val Black get() = scheme.bg
    val Surface get() = scheme.surface
    val Surface2 get() = scheme.surface2
    val Surface3 get() = scheme.surface3
    val Stroke get() = scheme.stroke
    val Text get() = scheme.text
    val Muted get() = scheme.muted
    val Faint get() = scheme.faint
    val Accent get() = scheme.accent
    val OnAccent get() = scheme.onAccent
    val Lan get() = scheme.lan
    val Tailscale get() = scheme.tailscale
    val Good get() = scheme.good
    val Searching get() = scheme.warn
    val Offline get() = scheme.bad

    /** A large-surface corner scaled to the chosen radius (24dp is the "design size"). */
    fun shape(base: Dp) = RoundedCornerShape(base * (radius / 24f))

    /** Glass only exists in black mode with the blur turned up. */
    val glass get() = black && blur > 0.02f
    val blurRadius get() = (6 + 34 * blur).dp
    /** How see-through glass surfaces are. */
    val glassAlpha get() = 0.55f + 0.25f * (1f - blur)
}

fun applyAppearance(a: Appearance) {
    Palette.black = a.black
    Palette.scheme = if (a.black) Schemes.Mono else Schemes.byId(a.scheme)
    Palette.blur = a.blur
    Palette.radius = if (a.black) a.blackRadius else a.radius
}

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
)

@Composable
fun HarborTheme(appearance: Appearance, content: @Composable () -> Unit) {
    applyAppearance(appearance)
    val s = Palette.scheme
    val colors = darkColorScheme(
        primary = s.accent, onPrimary = s.onAccent,
        secondary = s.tailscale, onSecondary = s.bg,
        background = s.bg, onBackground = s.text,
        surface = s.surface, onSurface = s.text,
        surfaceVariant = s.surface2, onSurfaceVariant = s.muted,
        surfaceContainer = s.surface2, surfaceContainerHigh = s.surface3, surfaceContainerLow = s.surface,
        surfaceContainerHighest = s.surface3, surfaceContainerLowest = s.bg,
        outline = s.stroke, outlineVariant = s.stroke, error = s.bad,
    )
    val shapes = Shapes(
        extraSmall = Palette.shape(8.dp), small = Palette.shape(12.dp), medium = Palette.shape(16.dp),
        large = Palette.shape(22.dp), extraLarge = Palette.shape(28.dp),
    )
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes) {
        CompositionLocalProvider(LocalContentColor provides s.text, content = content)
    }
}
