package ai.bonsen.docscan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bonsen.docscan.ScanViewModel
import ai.bonsen.docscan.scan.Filter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    vm: ScanViewModel,
    onAddPage: () -> Unit,
    onCrop: (String) -> Unit,
    onExit: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { vm.pages.size })
    var showSave by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }

    BackHandler { showDiscard = true }
    LaunchedEffect(vm.pages.size) {
        if (vm.pages.isEmpty() && !vm.saving) onExit()
    }

    val index = pagerState.currentPage.coerceIn(0, (vm.pages.size - 1).coerceAtLeast(0))
    val page = vm.pages.getOrNull(index)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { showDiscard = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        if (vm.pages.isEmpty()) "Review" else "Page ${index + 1} of ${vm.pages.size}",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                key = { vm.pages.getOrNull(it)?.id ?: it.toString() }
            ) { i ->
                val p = vm.pages.getOrNull(i)
                val bmp = p?.let { vm.previews[it.id] }
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (bmp != null) {
                        val img = remember(bmp) { bmp.asImageBitmap() }
                        Image(
                            bitmap = img,
                            contentDescription = "Scanned page",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.shadow(6.dp).background(Color.White)
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            // filters
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Filter.values().forEach { f ->
                    FilterChip(
                        selected = page?.filter == f,
                        onClick = { page?.let { vm.setFilter(it.id, f) } },
                        label = { Text(f.label) }
                    )
                }
            }

            // tools
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolButton(Icons.Default.PhotoCamera, "Add page") { onAddPage() }
                ToolButton(Icons.Default.Crop, "Crop") { page?.let { onCrop(it.id) } }
                ToolButton(Icons.Default.RotateRight, "Rotate") { page?.let { vm.rotate(it.id) } }
                ToolButton(Icons.Default.Delete, "Delete") { page?.let { vm.deletePage(it.id) } }
            }

            Button(
                onClick = { showSave = true },
                enabled = vm.pages.isNotEmpty() && vm.pending == 0,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save as PDF", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showSave) {
        var name by remember {
            mutableStateOf("Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date()))
        }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Name your PDF") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    showSave = false
                    vm.save(name, onExit)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard this scan?") },
            text = { Text("The pages you captured have not been saved yet.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    vm.clearPages()
                    onExit()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } }
        )
    }

    if (vm.saving) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Creating PDF…", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
