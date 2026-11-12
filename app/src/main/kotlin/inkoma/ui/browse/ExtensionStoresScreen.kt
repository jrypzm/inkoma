package inkoma.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import inkoma.Inkoma
import inkoma.domain.ExtensionStore
import inkoma.extension.ExtensionManager
import inkoma.ui.LocalSnackbar
import inkoma.ui.components.AppBar
import inkoma.ui.components.ConfirmDialog
import inkoma.ui.components.EmptyScreen
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons

/** Settings › Browse › Extension stores. */
class ExtensionStoresScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val uri = LocalUriHandler.current
        val clipboard = LocalClipboardManager.current
        val stores by remember { Inkoma.extensionStoreRepository.observeAll() }.collectAsState(emptyList())
        var adding by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<ExtensionStore?>(null) }
        Scaffold(
            topBar = {
                AppBar(title = "Extension stores", navigateUp = { navigator.pop() }, actions = {
                    IconButton(onClick = { Inkoma.extensionManager.refreshStores() }) { Icon(InkomaIcons.Refresh, "Refresh") }
                })
            },
            floatingActionButton = { ExtendedFloatingActionButton(text = { Text("Add") }, icon = { Icon(InkomaIcons.Add, null) }, onClick = { adding = true }) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (stores.isEmpty()) {
                    EmptyScreen("You have no extension stores. Tap the plus button to add one.")
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        items(stores, key = { it.indexUrl }) { store ->
                            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(store.name, style = MaterialTheme.typography.titleMedium)
                                        Text(store.indexUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { navigator.push(inkoma.ui.browser.BrowserScreen(store.contactWebsite, store.name)) }) { Icon(InkomaIcons.Public, "Website") }
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(store.indexUrl)) }) { Icon(InkomaIcons.Edit, "Copy URL") }
                                    IconButton(onClick = { deleting = store }) { Icon(InkomaIcons.Delete, "Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (adding) {
            var url by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { if (!busy) adding = false },
                title = { Text("Add store") },
                text = {
                    Column {
                        Text("Only add stores from sources you trust.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = url, onValueChange = { url = it; err = null }, singleLine = true, label = { Text("Store URL") },
                            isError = err != null, supportingText = { err?.let { Text(it) } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && url.isNotBlank(), onClick = {
                        busy = true
                        scope.launch {
                            when (val r = Inkoma.extensionManager.addStore(url)) {
                                ExtensionManager.AddStoreResult.Success -> { adding = false; snackbar.showSnackbar("Store added") }
                                ExtensionManager.AddStoreResult.Duplicate -> err = "This store already exists"
                                ExtensionManager.AddStoreResult.InvalidUrl -> err = "Invalid store URL"
                                is ExtensionManager.AddStoreResult.Error -> err = "Error: ${r.message}"
                            }
                            busy = false
                        }
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { adding = false }) { Text("Cancel") } },
            )
        }
        deleting?.let { s ->
            ConfirmDialog("Delete store", "Do you wish to delete the store \"${s.name}\"?", onConfirm = { Inkoma.extensionManager.removeStore(s) }, onDismiss = { deleting = null })
        }
    }
}
