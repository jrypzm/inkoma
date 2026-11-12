package inkoma.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import inkoma.Inkoma
import inkoma.core.DesktopIntegration
import inkoma.core.ThemeMode
import inkoma.extension.ExtensionManager
import inkoma.ui.browse.pickDirectory
import inkoma.ui.components.CheckboxItem
import inkoma.ui.components.RadioItem
import inkoma.ui.theme.InkomaIcons

/**
 * First-launch setup (Mihon's onboarding): theme, storage, an extension store and desktop integration.
 * [onFinished] is called once the user completes or skips it.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val steps = 5
    val finish = {
        Inkoma.preferences.onboardingComplete.set(true)
        onFinished()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(24.dp)) {
                LinearProgressIndicator(progress = { (step + 1f) / steps }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp).verticalScroll(rememberScrollState())) {
                    when (step) {
                        0 -> WelcomeStep()
                        1 -> ThemeStep()
                        2 -> StorageStep()
                        3 -> StoreStep()
                        else -> DesktopStep()
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (step == 0) {
                        TextButton(onClick = finish) { Text("Skip") }
                    } else {
                        TextButton(onClick = { step-- }) { Text("Back") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (step < steps - 1) step++ else finish() }) {
                        Text(if (step == 0) "Get started" else if (step < steps - 1) "Next" else "Finish")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
}

@Composable
private fun WelcomeStep() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource("icons/inkoma.png"), contentDescription = null, modifier = Modifier.size(96.dp))
        Text("Welcome!", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Let's set some things up first. You can always change these in the settings later.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ThemeStep() {
    val p = Inkoma.preferences
    val mode by p.themeMode.changes().collectAsState(p.themeMode.get())
    StepTitle("Theme", "Choose how Inkoma looks.")
    listOf(ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
        RadioItem(label, mode == m) { p.themeMode.set(m) }
    }
}

@Composable
private fun StorageStep() {
    val p = Inkoma.preferences
    val dir by p.downloadsDirectory.changes().collectAsState(p.downloadsDirectory.get())
    StepTitle("Storage", "Downloaded chapters are saved here.")
    Text(dir, style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { pickDirectory("Download location")?.let { p.downloadsDirectory.set(it.absolutePath) } }, modifier = Modifier.padding(top = 12.dp)) {
        Icon(InkomaIcons.Folder, null, Modifier.size(18.dp))
        Text("Choose folder", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun StoreStep() {
    val scope = rememberCoroutineScope()
    val stores by remember { Inkoma.extensionStoreRepository.observeAll() }.collectAsState(emptyList())
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    StepTitle(
        "Extensions",
        "Inkoma doesn't include any sources. Add an extension store to install extensions from Browse › Extensions. " +
            "Only add stores you trust.",
    )
    stores.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(InkomaIcons.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("${s.name} added", modifier = Modifier.padding(start = 8.dp))
        }
    }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it; message = null },
        label = { Text("Store URL") },
        singleLine = true,
        isError = message != null,
        supportingText = { message?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = { url = SUGGESTED_STORE; message = null }, label = { Text("Use community store (Keiyoushi)") })
        Spacer(Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Button(enabled = url.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                message = when (val r = Inkoma.extensionManager.addStore(url)) {
                    ExtensionManager.AddStoreResult.Success -> { url = ""; null }
                    ExtensionManager.AddStoreResult.Duplicate -> "This store already exists"
                    ExtensionManager.AddStoreResult.InvalidUrl -> "Invalid store URL"
                    is ExtensionManager.AddStoreResult.Error -> "Couldn't add store: ${r.message}"
                }
                busy = false
            }
        }) { Text("Add") }
    }
}

@Composable
private fun DesktopStep() {
    val p = Inkoma.preferences
    var installed by remember { mutableStateOf(DesktopIntegration.isMenuEntryInstalled()) }
    var error by remember { mutableStateOf<String?>(null) }
    val notifications by p.desktopNotifications.changes().collectAsState(p.desktopNotifications.get())
    StepTitle("Desktop", "Integrate Inkoma with your desktop.")
    if (DesktopIntegration.appImagePath != null) {
        if (installed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(InkomaIcons.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Inkoma is in your applications menu", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            OutlinedButton(onClick = {
                DesktopIntegration.installMenuEntry().onSuccess { installed = true }.onFailure { error = it.message }
            }) { Text("Add to applications menu") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    CheckboxItem("Show notifications for library updates and downloads", notifications) { p.desktopNotifications.set(!notifications) }
}

/** Shown as a one-click suggestion only; the user still confirms by pressing "Add". */
private const val SUGGESTED_STORE = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
