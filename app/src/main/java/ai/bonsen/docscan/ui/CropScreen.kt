package ai.bonsen.docscan.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bonsen.docscan.ScanViewModel
import ai.bonsen.docscan.scan.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private fun fitRect(box: IntSize, bw: Int, bh: Int, pad: Float): Rect {
    val availW = (box.width - 2 * pad).coerceAtLeast(1f)
    val availH = (box.height - 2 * pad).coerceAtLeast(1f)
    val s = min(availW / bw, availH / bh)
    val w = bw * s
    val h = bh * s
    return Rect((box.width - w) / 2f, (box.height - h) / 2f, (box.width + w) / 2f, (box.height + h) / 2f)
}

@Composable
fun CropScreen(vm: ScanViewModel, pageId: String, onClose: () -> Unit) {
    val page = vm.pages.firstOrNull { it.id == pageId }
    if (page == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pts by remember { mutableStateOf(page.corners.toList()) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val padPx = with(LocalDensity.current) { 24.dp.toPx() }

    LaunchedEffect(pageId) {
        bitmap = withContext(Dispatchers.Default) { ImageIO.decode(page.file, 1800) }
    }
    BackHandler(onBack = onClose)

    val bmp = bitmap
    val imageBitmap = remember(bmp) { bmp?.asImageBitmap() }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White) }
            Text(
                "Drag the corners to fit the page",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                vm.setCorners(pageId, pts.toFloatArray())
                onClose()
            }) { Text("Done") }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { boxSize = it }
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(boxSize, bmp) {
                        val b = bmp ?: return@pointerInput
                        val r = fitRect(boxSize, b.width, b.height, padPx)
                        val slop = 64.dp.toPx()
                        detectDragGestures(
                            onDragStart = { pos ->
                                var best = -1
                                var bestD = Float.MAX_VALUE
                                for (i in 0 until 4) {
                                    val p = Offset(r.left + pts[i * 2] * r.width, r.top + pts[i * 2 + 1] * r.height)
                                    val d = (p - pos).getDistance()
                                    if (d < bestD) {
                                        bestD = d; best = i
                                    }
                                }
                                active = if (bestD < slop) best else -1
                            },
                            onDrag = { change, amount ->
                                val i = active
                                if (i >= 0) {
                                    change.consume()
                                    val nx = (pts[i * 2] + amount.x / r.width).coerceIn(0f, 1f)
                                    val ny = (pts[i * 2 + 1] + amount.y / r.height).coerceIn(0f, 1f)
                                    pts = pts.toMutableList().also {
                                        it[i * 2] = nx
                                        it[i * 2 + 1] = ny
                                    }
                                }
                            },
                            onDragEnd = { active = -1 },
                            onDragCancel = { active = -1 }
                        )
                    }
            ) {
                val img = imageBitmap
                if (img != null && bmp != null) {
                    val r = fitRect(boxSize, bmp.width, bmp.height, padPx)
                    drawImage(
                        img,
                        dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                        dstSize = IntSize(r.width.toInt(), r.height.toInt())
                    )
                    val o = List(4) { Offset(r.left + pts[it * 2] * r.width, r.top + pts[it * 2 + 1] * r.height) }
                    val path = Path().apply {
                        moveTo(o[0].x, o[0].y)
                        lineTo(o[1].x, o[1].y)
                        lineTo(o[2].x, o[2].y)
                        lineTo(o[3].x, o[3].y)
                        close()
                    }
                    drawPath(path, Color(0x332563EB))
                    drawPath(path, Color(0xFF60A5FA), style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
                    for (p in o) {
                        drawCircle(Color.White, radius = 12.dp.toPx(), center = p)
                        drawCircle(Color(0xFF2563EB), radius = 12.dp.toPx(), center = p, style = Stroke(width = 3.dp.toPx()))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch { pts = vm.redetect(pageId).toList() }
            }) { Text("Auto-detect", color = Color.White) }
            TextButton(onClick = {
                pts = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            }) { Text("Full page", color = Color.White) }
        }
    }
}
