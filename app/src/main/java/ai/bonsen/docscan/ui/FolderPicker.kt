package ai.bonsen.docscan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bonsen.docscan.scan.Cloud
import ai.bonsen.docscan.scan.Cloud.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tap through OneDrive / SharePoint sites / Drive folders and pick one as the upload folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(provider: Cloud.Provider, onChoose: (List<Place>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val path = remember { mutableStateListOf<Place>() }
    var items by remember { mutableStateOf<List<Place>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }

    fun back() = if (path.size > 1) { path.removeAt(path.lastIndex); searched = null } else onClose()
    BackHandler { back() }

    LaunchedEffect(path.lastOrNull(), searched, reload) {
        items = null
        error = null
        try {
            val here = path.lastOrNull() ?: withContext(Dispatchers.IO) { Cloud.browseRoot(ctx, provider) }.also { path.add(it) }
            val q = searched
            items = withContext(Dispatchers.IO) {
                if (here == Place.Sites && q != null) Cloud.searchSites(ctx, q) else Cloud.browse(ctx, here)
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not load folders"
        }
    }

    val here = path.lastOrNull()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text(here?.name ?: "Choose a folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (path.size > 1) {
                            Text(
                                path.dropLast(1).filter { it != Place.Sites }.joinToString(" › ") { it.name },
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (here is Place.Folder) {
                BottomAppBar {
                    TextButton(onClick = { naming = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("New folder")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onChoose(path.toList()) }, modifier = Modifier.padding(end = 8.dp)) { Text("Use this folder") }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (here == Place.Sites) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (it.isBlank()) searched = null },
                    placeholder = { Text("Search SharePoint sites") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searched = query.trim().ifEmpty { null } }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            val list = items
            when {
                error != null -> Message(error!!) { TextButton(onClick = { reload++ }) { Text("Try again") } }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Message(
                    when {
                        searched != null -> "No sites match \"$searched\"."
                        here is Place.Folder -> "No folders here. Use this one, or create a new folder."
                        else -> "Nothing here."
                    }
                )
                else -> LazyColumn {
                    items(list) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { path.add(p); searched = null }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (p is Place.Site) Icons.Default.Domain else Icons.Default.Folder,
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(p.name, Modifier.weight(1f), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (naming && here is Place.Folder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Folder name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { Cloud.createFolder(ctx, here, name.trim()) }
                            path.add(f)     // open it, ready for "Use this folder"
                        } catch (e: Exception) {
                            error = "Could not create the folder: ${e.message}"
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}
