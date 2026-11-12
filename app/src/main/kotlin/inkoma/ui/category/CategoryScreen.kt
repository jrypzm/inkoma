package inkoma.ui.category

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import inkoma.Inkoma
import inkoma.domain.Category
import inkoma.ui.components.AppBar
import inkoma.ui.components.ConfirmDialog
import inkoma.ui.components.EmptyScreen
import inkoma.ui.navigation.LocalNavigator
import inkoma.ui.navigation.Screen
import inkoma.ui.theme.InkomaIcons

/** Mihon's CategoryScreen: create, rename, reorder, delete. */
class CategoryScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val all by remember { Inkoma.categoryRepository.observeAll() }.collectAsState(emptyList())
        val categories = all.filterNot { it.isSystemCategory }
        var creating by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf<Category?>(null) }
        var deleting by remember { mutableStateOf<Category?>(null) }

        Scaffold(
            topBar = { AppBar(title = "Edit categories", navigateUp = { navigator.pop() }) },
            floatingActionButton = {
                ExtendedFloatingActionButton(text = { Text("Add") }, icon = { Icon(InkomaIcons.Add, null) }, onClick = { creating = true })
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (categories.isEmpty()) {
                    EmptyScreen("You have no categories. Tap the plus button to create one for organizing your library.")
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        itemsIndexed(categories, key = { _, c -> c.id }) { index, cat ->
                            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(InkomaIcons.Label, null)
                                    Text(cat.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
                                    IconButton(enabled = index > 0, onClick = {
                                        val ids = categories.map { it.id }.toMutableList()
                                        ids.add(index - 1, ids.removeAt(index))
                                        Inkoma.categoryRepository.reorder(ids)
                                    }) { Icon(InkomaIcons.ArrowDropUp, "Move up") }
                                    IconButton(enabled = index < categories.lastIndex, onClick = {
                                        val ids = categories.map { it.id }.toMutableList()
                                        ids.add(index + 1, ids.removeAt(index))
                                        Inkoma.categoryRepository.reorder(ids)
                                    }) { Icon(InkomaIcons.ArrowDropDown, "Move down") }
                                    IconButton(onClick = { renaming = cat }) { Icon(InkomaIcons.Edit, "Rename") }
                                    IconButton(onClick = { deleting = cat }) { Icon(InkomaIcons.Delete, "Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (creating) {
            NameDialog("Add category", "", categories.map { it.name }, onDismiss = { creating = false }) { Inkoma.categoryRepository.insert(it) }
        }
        renaming?.let { cat ->
            NameDialog("Rename category", cat.name, categories.filter { it.id != cat.id }.map { it.name }, onDismiss = { renaming = null }) {
                Inkoma.categoryRepository.rename(cat.id, it)
            }
        }
        deleting?.let { cat ->
            ConfirmDialog("Delete category", "Do you wish to delete the category \"${cat.name}\"?", onConfirm = { Inkoma.categoryRepository.delete(cat.id) }, onDismiss = { deleting = null })
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, existing: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val exists = existing.any { it.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = exists,
                supportingText = { if (exists) Text("A category with this name already exists!") },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !exists, onClick = { onConfirm(name.trim()); onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
