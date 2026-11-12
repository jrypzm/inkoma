package inkoma.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import inkoma.Inkoma
import inkoma.extension.ContentWarning
import inkoma.extension.Extension
import inkoma.extension.InstallStep
import inkoma.ui.components.EmptyScreen
import inkoma.ui.components.ListGroupHeader
import inkoma.ui.components.RemoteImage
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.theme.InkomaIcons

class ExtensionsTab {
    private var query by mutableStateOf("")

    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val installed by Inkoma.extensionManager.installed.collectAsState()
        val notLoaded by Inkoma.extensionManager.notLoaded.collectAsState()
        val available by Inkoma.extensionManager.available.collectAsState()
        val steps by Inkoma.extensionManager.installSteps.collectAsState()
        val refreshing by Inkoma.extensionManager.refreshing.collectAsState()
        val stores by remember { Inkoma.extensionStoreRepository.observeAll() }.collectAsState(emptyList())
        val langs by Inkoma.preferences.enabledLanguages.changes().collectAsState(Inkoma.preferences.enabledLanguages.get())
        val showNsfw by Inkoma.preferences.showNsfwSource.changes().collectAsState(Inkoma.preferences.showNsfwSource.get())
        var trustTarget by remember { mutableStateOf<Extension.NotLoaded?>(null) }

        fun match(name: String) = query.isBlank() || name.contains(query, ignoreCase = true)
        val installedPkgs = (installed.map { it.pkgName } + notLoaded.map { it.pkgName }).toSet()
        val updates = installed.filter { it.hasUpdate && match(it.name) }
        val others = installed.filter { !it.hasUpdate && match(it.name) }
        val broken = notLoaded.filter { match(it.name) }
        val avail = available.filter { it.pkgName !in installedPkgs && match(it.name) && (it.lang in langs || it.sources.any { s -> s.lang in langs }) &&
            (showNsfw || it.contentWarning == ContentWarning.SAFE) }
            .groupBy { it.lang }.toSortedMap(compareBy { languageName(it) })

        Column(Modifier.fillMaxSize()) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search…") },
                singleLine = true,
                leadingIcon = { Icon(InkomaIcons.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (stores.isEmpty() && installed.isEmpty() && notLoaded.isEmpty()) {
                EmptyScreen("No extension stores added. Add one to browse and install extensions.") {
                    TextButton(onClick = { navigator.push(ExtensionStoresScreen()) }) { Text("Extension stores") }
                }
                return
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (updates.isNotEmpty()) {
                    item("h-updates") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ListGroupHeader("Updates pending") }
                            TextButton(onClick = { Inkoma.extensionManager.updateAll() }, modifier = Modifier.padding(end = 8.dp)) { Text("Update all") }
                        }
                    }
                    items(updates, key = { "u-${it.pkgName}" }) { ext ->
                        ExtensionRow(ext.name, ext.versionName, ext.lang, ext.contentWarning, ext.iconPath, null, steps[ext.pkgName],
                            onClick = { navigator.push(ExtensionDetailsScreen(ext.pkgName)) }) {
                            TextButton(onClick = { Inkoma.extensionManager.update(ext.pkgName) }) { Text("Update") }
                        }
                    }
                }
                if (others.isNotEmpty() || broken.isNotEmpty()) {
                    item("h-installed") { ListGroupHeader("Installed") }
                    items(others, key = { "i-${it.pkgName}" }) { ext ->
                        ExtensionRow(ext.name, ext.versionName + if (ext.isObsolete) " • Obsolete" else "", ext.lang, ext.contentWarning, ext.iconPath, null, steps[ext.pkgName],
                            onClick = { navigator.push(ExtensionDetailsScreen(ext.pkgName)) }) {
                            IconButton(onClick = { navigator.push(ExtensionDetailsScreen(ext.pkgName)) }) { Icon(InkomaIcons.Settings, "Settings") }
                        }
                    }
                    items(broken, key = { "b-${it.pkgName}" }) { ext ->
                        val reason = when (val r = ext.reason) {
                            is Extension.NotLoaded.Reason.Untrusted -> "Untrusted"
                            Extension.NotLoaded.Reason.UnsupportedLibVersion -> "Unsupported lib version (${ext.libVersion})"
                            Extension.NotLoaded.Reason.Unsigned -> "Unsigned"
                            Extension.NotLoaded.Reason.Malformed -> "Malformed"
                            Extension.NotLoaded.Reason.Filtered -> "Hidden by content settings"
                            is Extension.NotLoaded.Reason.Failed -> "Failed to load: ${r.message}"
                        }
                        ExtensionRow(ext.name, "${ext.versionName} • $reason", null, ext.contentWarning, ext.iconPath, MaterialTheme.colorScheme.error, steps[ext.pkgName],
                            onClick = { if (ext.reason is Extension.NotLoaded.Reason.Untrusted) trustTarget = ext }) {
                            if (ext.reason is Extension.NotLoaded.Reason.Untrusted) {
                                TextButton(onClick = { trustTarget = ext }) { Text("Trust") }
                            } else {
                                TextButton(onClick = { Inkoma.extensionManager.uninstall(ext.pkgName) }) { Text("Uninstall") }
                            }
                        }
                    }
                }
                avail.forEach { (lang, list) ->
                    item("h-$lang") { ListGroupHeader(languageName(lang)) }
                    items(list, key = { "a-${it.pkgName}" }) { ext ->
                        ExtensionRow(ext.name, ext.versionName, null, ext.contentWarning, ext.iconUrl, null, steps[ext.pkgName], onClick = {}) {
                            val step = steps[ext.pkgName]
                            if (step == null || step == InstallStep.Error) {
                                TextButton(onClick = { Inkoma.extensionManager.install(ext) }) { Text(if (step == InstallStep.Error) "Retry" else "Install") }
                            } else {
                                IconButton(onClick = { Inkoma.extensionManager.cancelInstall(ext.pkgName) }) { Icon(InkomaIcons.Close, "Cancel") }
                            }
                        }
                    }
                }
            }
        }

        trustTarget?.let { ext ->
            val sig = (ext.reason as? Extension.NotLoaded.Reason.Untrusted)?.signature ?: ""
            AlertDialog(
                onDismissRequest = { trustTarget = null },
                title = { Text("Untrusted extension") },
                text = {
                    Text(
                        "This extension was signed by an untrusted certificate and wasn't activated.\n\n" +
                            "A malicious extension could read any stored login credentials or execute arbitrary code.\n\n" +
                            "By trusting this certificate you accept these risks.\n\nSignature: $sig",
                    )
                },
                confirmButton = { TextButton(onClick = { Inkoma.extensionManager.trust(ext.pkgName, ext.versionCode, sig); trustTarget = null }) { Text("Trust") } },
                dismissButton = { TextButton(onClick = { Inkoma.extensionManager.uninstall(ext.pkgName); trustTarget = null }) { Text("Uninstall") } },
            )
        }
    }
}

@Composable
private fun ExtensionRow(
    name: String,
    version: String,
    lang: String?,
    warning: ContentWarning,
    icon: String?,
    subtitleColor: androidx.compose.ui.graphics.Color?,
    step: InstallStep?,
    onClick: () -> Unit,
    action: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) {
            val url = icon?.let { if (it.startsWith("http")) it else java.io.File(it).toURI().toString() }
            if (url != null) RemoteImage(url, null, Modifier.fillMaxSize(), maxSide = 96) else Icon(InkomaIcons.Extension, null, Modifier.fillMaxSize().padding(6.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = listOfNotNull(lang?.let { languageName(it) }, version, when (warning) { ContentWarning.NSFW -> "18+"; ContentWarning.MIXED -> "Mixed"; else -> null })
            Text(
                when (step) {
                    InstallStep.Pending -> "Pending"
                    InstallStep.Downloading -> "Downloading"
                    InstallStep.Installing -> "Installing"
                    InstallStep.Error -> "Installation error"
                    else -> parts.joinToString(" • ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (step == InstallStep.Downloading || step == InstallStep.Installing || step == InstallStep.Pending) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        action()
    }
}
