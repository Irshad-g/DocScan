package ai.bonsen.docscan.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.widget.Toast
import ai.bonsen.docscan.ScanViewModel
import ai.bonsen.docscan.scan.Backup
import ai.bonsen.docscan.scan.Cloud
import ai.bonsen.docscan.scan.SavedDoc
import ai.bonsen.docscan.scan.Storage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ScanViewModel, signIn: SignIn, onScan: () -> Unit, onImported: () -> Unit, onChooseFolder: (Cloud.Provider) -> Unit) {
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf<SavedDoc?>(null) }
    var deleting by remember { mutableStateOf<SavedDoc?>(null) }
    var cloud by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.addUris(uris) { onImported() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DocScan", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { cloud = true }) {
                        Icon(Icons.Default.Cloud, contentDescription = "Account and cloud backup")
                    }
                    IconButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.PhotoLibrary, contentDescription = "Import photos") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                text = { Text("Scan", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (vm.docs.isEmpty()) {
                EmptyState(vm.pending > 0)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vm.docs, key = { it.name }) { doc ->
                        DocRow(
                            doc = doc,
                            onOpen = { Storage.open(ctx, doc) },
                            onShare = { Storage.share(ctx, doc) },
                            onBackup = {
                                val text = when (doc.backup) {
                                    Backup.DONE -> "Backed up"
                                    Backup.FAILED -> "Upload failed: ${doc.backupError}. Retrying."
                                    Backup.PENDING -> if (vm.account == null) "Not backed up: sign in from the cloud icon" else "Waiting to upload (needs internet)"
                                }
                                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
                                if (doc.backup == Backup.FAILED) vm.retryFailed()
                            },
                            onRename = { renaming = doc },
                            onDelete = { deleting = doc }
                        )
                    }
                }
            }
        }
    }

    if (cloud && vm.account == null) {
        AlertDialog(
            onDismissRequest = { cloud = false },
            title = { Text("Cloud backup") },
            text = { Text("Sign in to back up scans automatically.", fontSize = 14.sp) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { cloud = false; signIn.microsoft() }) { Text("Microsoft (SharePoint)") }
                    TextButton(onClick = { cloud = false; signIn.google() }) { Text("Google (Drive)") }
                    TextButton(onClick = { cloud = false }) { Text("Not now") }
                }
            }
        )
    } else if (cloud) {
        AlertDialog(
            onDismissRequest = { cloud = false },
            title = { Text("Cloud backup") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("New scans upload automatically when online.", fontSize = 14.sp)
                    for (p in Cloud.Provider.entries) {
                        Spacer(Modifier.height(12.dp))
                        Text(p.service, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val email = vm.accounts[p]
                        if (email == null) {
                            TextButton(onClick = {
                                cloud = false
                                if (p == Cloud.Provider.GOOGLE) signIn.google() else signIn.microsoft()
                            }) { Text("Sign in") }
                            continue
                        }
                        Text("$email\nFolder: ${vm.destinations[p]}", fontSize = 14.sp)
                        Row {
                            TextButton(onClick = {
                                cloud = false
                                onChooseFolder(p)
                            }) { Text("Change folder") }
                            TextButton(onClick = { vm.signOut(p) }) { Text("Sign out") }
                        }
                    }
                    if (vm.accounts.size > 1) {
                        val both = vm.uploadTargets.size > 1
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Upload to both", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(checked = both, onCheckedChange = vm::uploadToBoth)
                        }
                        if (!both) for (p in Cloud.Provider.entries) {
                            Row(
                                Modifier.fillMaxWidth().selectable(
                                    selected = p in vm.uploadTargets, role = Role.RadioButton, onClick = { vm.uploadOnlyTo(p) }
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = p in vm.uploadTargets, onClick = null)
                                Text("Only ${p.service}", fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { cloud = false }) { Text("Done") }
            }
        )
    }

    renaming?.let { doc ->
        var text by remember(doc.name) { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(doc, text)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete scan?") },
            text = { Text("\"${doc.name}\" will be removed from the app. A copy in ${Storage.PUBLIC_FOLDER} stays untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(doc)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }

    vm.lastSaved?.let { doc ->
        AlertDialog(
            onDismissRequest = { vm.lastSaved = null },
            title = { Text("PDF saved") },
            text = { Text("\"${doc.name}.pdf\" is in your library and in ${Storage.PUBLIC_FOLDER}.") },
            confirmButton = {
                Button(onClick = {
                    Storage.share(ctx, doc)
                    vm.lastSaved = null
                }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        Storage.open(ctx, doc)
                        vm.lastSaved = null
                    }) { Text("Open") }
                    TextButton(onClick = { vm.lastSaved = null }) { Text("Done") }
                }
            }
        )
    }
}

@Composable
private fun EmptyState(busy: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.PictureAsPdf,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("No scans yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (busy) "Preparing your images…" else "Tap Scan to turn paper into a clean PDF.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun DocRow(
    doc: SavedDoc,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onBackup: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val thumb = remember(doc.thumb?.path, doc.modified) {
        doc.thumb?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
    val date = remember(doc.modified) {
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(doc.modified))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name, fontWeight = FontWeight.SemiBold, maxLines = 2, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (doc.backup) {
                        Backup.DONE -> Icons.Default.CloudDone
                        Backup.PENDING -> Icons.Default.CloudUpload
                        Backup.FAILED -> Icons.Default.CloudOff
                    },
                    contentDescription = when (doc.backup) {
                        Backup.DONE -> "Backed up"
                        Backup.PENDING -> "Not backed up yet"
                        Backup.FAILED -> "Backup failed"
                    },
                    tint = when (doc.backup) {
                        Backup.DONE -> MaterialTheme.colorScheme.primary
                        Backup.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        Backup.FAILED -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(16.dp).clickable(onClick = onBackup)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$date  ·  ${doc.pages} ${if (doc.pages == 1) "page" else "pages"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
        IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { menu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}
