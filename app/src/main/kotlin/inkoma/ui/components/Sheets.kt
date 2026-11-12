package inkoma.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Mihon's AdaptiveSheet as shown on large screens: a centered, rounded surface. */
@Composable
fun AdaptiveSheet(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 640.dp).padding(vertical = 24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
        ) {
            content()
        }
    }
}

/** Sheet with tabs (Mihon's TabbedDialog), each tab's content scrollable. */
@Composable
fun TabbedSheet(onDismissRequest: () -> Unit, tabTitles: List<String>, content: @Composable (Int) -> Unit) {
    AdaptiveSheet(onDismissRequest) {
        var selected by remember { mutableIntStateOf(0) }
        Column {
            PrimaryTabRow(selectedTabIndex = selected, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                tabTitles.forEachIndexed { i, t ->
                    Tab(selected = selected == i, onClick = { selected = i }, text = { Text(t) })
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) { content(selected) }
        }
    }
}
