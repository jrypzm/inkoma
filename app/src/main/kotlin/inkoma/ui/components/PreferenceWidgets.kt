package inkoma.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import inkoma.core.Preference
import inkoma.core.TriState
import inkoma.ui.theme.InkomaIcons

private val PrefsHorizontalPadding = 16.dp
private val PrefsVerticalPadding = 16.dp

@Composable
fun PreferenceGroupHeader(title: String) {
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 14.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun BasePreferenceWidget(
    title: String?,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    widget: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.padding(start = PrefsHorizontalPadding, end = 8.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
        Column(
            Modifier.weight(1f).padding(horizontal = PrefsHorizontalPadding, vertical = PrefsVerticalPadding / 2),
            verticalArrangement = Arrangement.Center,
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    maxLines = 10,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
        if (widget != null) Box(Modifier.padding(end = PrefsHorizontalPadding)) { widget() }
    }
}

@Composable
fun TextPreferenceWidget(title: String, subtitle: String? = null, icon: ImageVector? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    BasePreferenceWidget(title = title, subtitle = subtitle, icon = icon, enabled = enabled, onClick = onClick)
}

@Composable
fun SwitchPreferenceWidget(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onCheckedChanged: (Boolean) -> Unit) {
    BasePreferenceWidget(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { onCheckedChanged(!checked) },
        widget = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
fun SwitchPreference(pref: Preference<Boolean>, title: String, subtitle: String? = null, enabled: Boolean = true) {
    val value by pref.changes().collectAsState(pref.get())
    SwitchPreferenceWidget(title, subtitle, value, enabled) { pref.set(it) }
}

@Composable
fun <T> ListPreferenceWidget(
    title: String,
    value: T,
    entries: List<Pair<T, String>>,
    subtitle: String? = entries.firstOrNull { it.first == value }?.second,
    enabled: Boolean = true,
    onValueChange: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextPreferenceWidget(title = title, subtitle = subtitle, enabled = enabled, onClick = { open = true })
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(entries) { (v, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = v == value, onClick = {
                                onValueChange(v)
                                open = false
                            }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v == value, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun <T> ListPreference(pref: Preference<T>, title: String, entries: List<Pair<T, String>>, subtitleOverride: String? = null) {
    val value by pref.changes().collectAsState(pref.get())
    ListPreferenceWidget(
        title = title,
        value = value,
        entries = entries,
        subtitle = subtitleOverride ?: entries.firstOrNull { it.first == value }?.second,
        onValueChange = { pref.set(it) },
    )
}

@Composable
fun MultiSelectListPreferenceWidget(
    title: String,
    subtitle: String?,
    values: Set<String>,
    entries: List<Pair<String, String>>,
    onValuesChange: (Set<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextPreferenceWidget(title = title, subtitle = subtitle, onClick = { open = true })
    if (open) {
        var selected by remember { mutableStateOf(values) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(entries) { (key, label) ->
                        LabeledCheckbox(label = label, checked = key in selected) { checked ->
                            selected = if (checked) selected + key else selected - key
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValuesChange(selected)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** Include/exclude category picker used by "Categories" in library update / auto-download settings. */
@Composable
fun TriStateListDialogPreference(
    title: String,
    subtitle: String?,
    entries: List<Pair<String, String>>,
    included: Set<String>,
    excluded: Set<String>,
    onValuesChange: (Set<String>, Set<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextPreferenceWidget(title = title, subtitle = subtitle, onClick = { open = true })
    if (open) {
        var inc by remember { mutableStateOf(included) }
        var exc by remember { mutableStateOf(excluded) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(entries) { (key, label) ->
                        val state = when (key) {
                            in inc -> TriState.ENABLED_IS
                            in exc -> TriState.ENABLED_NOT
                            else -> TriState.DISABLED
                        }
                        TriStateItem(label = label, state = state) {
                            when (state) {
                                TriState.DISABLED -> { inc = inc + key; exc = exc - key }
                                TriState.ENABLED_IS -> { inc = inc - key; exc = exc + key }
                                TriState.ENABLED_NOT -> { exc = exc - key }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValuesChange(inc, exc)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun EditTextPreferenceWidget(title: String, subtitle: String?, value: String, onConfirm: (String) -> Boolean) {
    var open by remember { mutableStateOf(false) }
    TextPreferenceWidget(title = title, subtitle = subtitle, onClick = { open = true })
    if (open) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { if (onConfirm(text)) open = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SliderPreferenceWidget(title: String, value: Int, valueRange: IntRange, valueText: String, onValueChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
fun LabeledCheckbox(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Mihon's sheet rows (TriStateItem / CheckboxItem / SortItem / RadioItem). */
@Composable
fun TriStateItem(label: String, state: TriState, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = when (state) {
                TriState.DISABLED -> ToggleableState.Off
                TriState.ENABLED_IS -> ToggleableState.On
                TriState.ENABLED_NOT -> ToggleableState.Indeterminate
            },
            onClick = null,
            enabled = enabled,
        )
        Text(label, modifier = Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun CheckboxItem(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SortItem(label: String, sortDescending: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp)) {
            when (sortDescending) {
                true -> Icon(InkomaIcons.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
                false -> Icon(InkomaIcons.ArrowDropUp, null, tint = MaterialTheme.colorScheme.primary)
                null -> {}
            }
        }
        Text(label, modifier = Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun RadioItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HeadingItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String?, confirmText: String = "OK", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
