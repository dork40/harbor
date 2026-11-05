package dev.dorki.harbor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dorki.harbor.ui.theme.Palette

@Composable
fun PageTitle(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(
                subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                fontFamily = if (Palette.black) FontFamily.Monospace else null,
            )
            Text(title, style = MaterialTheme.typography.headlineLarge)
        }
        trailing()
    }
}

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent, checkedBorderColor = Palette.Accent,
    uncheckedTrackColor = Palette.Surface3, uncheckedThumbColor = Palette.Muted, uncheckedBorderColor = Palette.Stroke,
)

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Stroke,
)

@Composable
fun Hairline() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Palette.Stroke))
}

/** One line inside a Panel: title and optional subtitle on the left, a control on the right. */
@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, control: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        control()
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) { Switch(checked, onChange, colors = switchColors()) }

@Composable
fun SettingSlider(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Slider(value, onChange, valueRange = range, colors = sliderColors())
    }
}
