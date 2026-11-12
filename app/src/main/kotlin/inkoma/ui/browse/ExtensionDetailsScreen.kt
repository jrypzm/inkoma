package inkoma.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.EditText
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.preferenceKey
import inkoma.Inkoma
import inkoma.ui.components.AppBar
import inkoma.ui.components.ConfirmDialog
import inkoma.ui.components.EditTextPreferenceWidget
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.ListPreferenceWidget
import inkoma.ui.components.MultiSelectListPreferenceWidget
import inkoma.ui.components.PreferenceGroupHeader
import inkoma.ui.components.RemoteImage
import inkoma.ui.components.SwitchPreferenceWidget
import inkoma.ui.components.TextPreferenceWidget
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons

class ExtensionDetailsScreen(private val pkgName: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val installed by Inkoma.extensionManager.installed.collectAsState()
        val disabled by Inkoma.preferences.disabledSources.changes().collectAsState(Inkoma.preferences.disabledSources.get())
        var confirmUninstall by remember { mutableStateOf(false) }
        val ext = installed.firstOrNull { it.pkgName == pkgName }
        Scaffold(topBar = { AppBar(title = "Extension info", navigateUp = { navigator.pop() }) }) { padding ->
            if (ext == null) {
                Box(Modifier.padding(padding)) { EmptyScreen("Extension not installed") }
                return@Scaffold
            }
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(112.dp)) {
                            ext.iconPath?.let { RemoteImage(java.io.File(it).toURI().toString(), null, Modifier.fillMaxSize(), maxSide = 192) }
                                ?: Icon(InkomaIcons.Extension, null, Modifier.fillMaxSize())
                        }
                        Text(ext.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                        SelectionContainer { Text(ext.pkgName, style = MaterialTheme.typography.bodySmall) }
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            InfoText(ext.versionName, "Version")
                            InfoText(languageName(ext.lang), "Language")
                            InfoText(ext.store?.badgeLabel ?: "—", "Store")
                        }
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedButton(onClick = { confirmUninstall = true }) { Text("Uninstall") }
                            if (ext.hasUpdate) Button(onClick = { Inkoma.extensionManager.update(ext.pkgName) }) { Text("Update") }
                        }
                    }
                    HorizontalDivider()
                }
                items(ext.sources, key = { it.id }) { source ->
                    val enabled = source.id.toString() !in disabled
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (ext.sources.size > 1) "${source.name} (${languageName(source.lang)})" else source.name, style = MaterialTheme.typography.bodyLarge)
                            Text(source.id.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (source is ConfigurableSource) {
                            IconButton(onClick = { navigator.push(SourcePreferencesScreen(source.id)) }) { Icon(InkomaIcons.Settings, "Settings") }
                        }
                        Switch(checked = enabled, onCheckedChange = { on ->
                            val set = Inkoma.preferences.disabledSources.get()
                            Inkoma.preferences.disabledSources.set(if (on) set - source.id.toString() else set + source.id.toString())
                        })
                    }
                }
            }
        }
        if (confirmUninstall) {
            ConfirmDialog("Uninstall", "Uninstall ${ext?.name}?", onConfirm = {
                Inkoma.extensionManager.uninstall(pkgName)
                navigator.pop()
            }, onDismiss = { confirmUninstall = false })
        }
    }

    @Composable
    private fun InfoText(primary: String, secondary: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(primary, style = MaterialTheme.typography.titleSmall)
            Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Renders a ConfigurableSource's androidx.preference screen with Mihon's preference widgets. */
class SourcePreferencesScreen(private val sourceId: Long) : Screen() {
    private var screenModel: PreferenceScreen? = null

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val source = Inkoma.sourceManager.get(sourceId) as? ConfigurableSource
        Scaffold(topBar = { AppBar(title = source?.toString() ?: "Source settings", navigateUp = { navigator.pop() }) }) { padding ->
            if (source == null) {
                Box(Modifier.padding(padding)) { EmptyScreen("Source not installed") }
                return@Scaffold
            }
            val error = remember { mutableStateOf<String?>(null) }
            val screen = remember {
                screenModel ?: runCatching {
                    val prefs = Inkoma.application.getSharedPreferences(source.preferenceKey(), android.content.Context.MODE_PRIVATE)
                    PreferenceScreen(Inkoma.application, prefs).also { source.setupPreferenceScreen(it) }
                }.onFailure { error.value = it.message ?: it.javaClass.simpleName }.getOrNull()?.also { screenModel = it }
            }
            if (screen == null) {
                Box(Modifier.padding(padding)) { EmptyScreen(error.value ?: "No settings") }
                return@Scaffold
            }
            var tick by remember { mutableIntStateOf(0) }
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(screen.flatten()) { pref ->
                    @Suppress("UNUSED_VARIABLE") val t = tick + screen.childRevision
                    if (pref.isVisible()) PreferenceItem(pref) { tick++ }
                }
            }
        }
    }

    @Composable
    private fun PreferenceItem(pref: Preference, onChanged: () -> Unit) {
        val title = pref.getTitle()?.toString().orEmpty()
        val summary = pref.getSummary()?.toString()
        when (pref) {
            is PreferenceCategory -> PreferenceGroupHeader(title)
            is TwoStatePreference -> SwitchPreferenceWidget(title, summary, pref.isChecked(), pref.isEnabled()) { v ->
                if (pref.callChangeListener(v)) pref.setChecked(v)
                onChanged()
            }
            is ListPreference -> ListPreferenceWidget(
                title = (pref as DialogPreference).getDialogTitle()?.toString() ?: title,
                value = pref.getValue(),
                entries = pref.getEntryValues().map { it.toString() }.zip(pref.getEntries().map { it.toString() }),
                subtitle = summary ?: pref.getEntry()?.toString(),
                enabled = pref.isEnabled(),
            ) { v ->
                if (pref.callChangeListener(v)) pref.setValue(v)
                onChanged()
            }
            is MultiSelectListPreference -> MultiSelectListPreferenceWidget(
                title = title,
                subtitle = summary,
                values = pref.getValues(),
                entries = pref.getEntryValues().map { it.toString() }.zip(pref.getEntries().map { it.toString() }),
            ) { v ->
                if (pref.callChangeListener(v)) pref.setValues(v)
                onChanged()
            }
            is EditTextPreference -> EditTextPreferenceWidget(title, summary ?: pref.getText(), pref.getText().orEmpty()) { v ->
                val editText = EditText(Inkoma.application).apply { setText(v) }
                pref.getOnBindEditTextListener()?.onBindEditText(editText)
                val ok = pref.callChangeListener(v)
                if (ok) pref.setText(v)
                onChanged()
                ok
            }
            else -> TextPreferenceWidget(title, summary, enabled = pref.isEnabled(), onClick = { pref.performClick(); onChanged() })
        }
    }
}

