package ai.bonsen.docscan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ai.bonsen.docscan.ScanViewModel
import ai.bonsen.docscan.scan.LiveDetector
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScreen(vm: ScanViewModel, onDone: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.addUris(uris) { }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }
    BackHandler(onBack = onClose)

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera access is needed to scan documents.", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.padding(8.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Import photos instead") }
            TextButton(onClick = onClose) { Text("Back") }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var liveCorners by remember { mutableStateOf<FloatArray?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var waitingForNext by remember { mutableStateOf(false) }
    var shots by remember { mutableStateOf(0) }       // bumps on every capture, drives the flash animation
    val flash = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val shutterSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { shutterSound.release() } }
    val shotInFlight = remember { AtomicBoolean(false) }

    val takePhoto: () -> Unit = takePhoto@{
        val capture = imageCapture ?: return@takePhoto
        if (!shotInFlight.compareAndSet(false, true)) return@takePhoto
        shots++
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val tmp = File(ctx.cacheDir, "cap_${System.nanoTime()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    vm.addCaptured(tmp)
                    shotInFlight.set(false)
                }

                override fun onError(exception: ImageCaptureException) {
                    shotInFlight.set(false)
                    Toast.makeText(ctx, "Capture failed, try again", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    val takePhotoRef = rememberUpdatedState(takePhoto)

    val detector = remember {
        LiveDetector(ContextCompat.getMainExecutor(ctx)) { corners, ready, waiting ->
            liveCorners = corners
            waitingForNext = waiting
            if (ready) takePhotoRef.value()
        }
    }
    SideEffect { detector.autoEnabled = vm.autoCapture }

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(executor, detector) }
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                imageCapture = capture
            } catch (e: Exception) {
                Log.e("DocScan", "Camera start failed", e)
                Toast.makeText(ctx, "Could not start the camera on this device", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }
    LaunchedEffect(shots) {
        if (shots > 0) {
            flash.snapTo(0.9f)
            flash.animateTo(0f, tween(450))
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
            Box(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (vm.autoCapture) Color(0xFF2563EB) else Color(0x33FFFFFF))
                    .clickable { vm.autoCapture = !vm.autoCapture }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    if (vm.autoCapture) "Auto capture: On" else "Auto capture: Off",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(Modifier.width(8.dp))
            IconButton(onClick = { torchOn = !torchOn }) {
                Icon(
                    if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Light",
                    tint = Color.White
                )
            }
        }

        // preview + live outline
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(3f / 4f)) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    // Faint quadrant guide: one page corner per quadrant is what the detector expects.
                    val guide = Color.White.copy(alpha = 0.3f)
                    val stroke = 1.dp.toPx()
                    drawLine(guide, androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
                        androidx.compose.ui.geometry.Offset(size.width / 2, size.height), stroke)
                    drawLine(guide, androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        androidx.compose.ui.geometry.Offset(size.width, size.height / 2), stroke)
                    val c = liveCorners
                    if (c != null) {
                        val path = Path().apply {
                            moveTo(c[0] * size.width, c[1] * size.height)
                            lineTo(c[2] * size.width, c[3] * size.height)
                            lineTo(c[4] * size.width, c[5] * size.height)
                            lineTo(c[6] * size.width, c[7] * size.height)
                            close()
                        }
                        // Green while the captured page is still in view, blue while looking for the next one.
                        val accent = if (waitingForNext) Color(0xFF16A34A) else Color(0xFF2563EB)
                        drawPath(path, accent.copy(alpha = 0.18f))
                        drawPath(path, accent, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
                        for (i in 0 until 4) {
                            val point = androidx.compose.ui.geometry.Offset(
                                c[i * 2] * size.width,
                                c[i * 2 + 1] * size.height
                            )
                            drawCircle(Color.White, radius = 11.dp.toPx(), center = point)
                            drawCircle(accent, radius = 11.dp.toPx(), center = point, style = Stroke(width = 3.dp.toPx()))
                        }
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x99000000))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        when {
                            waitingForNext -> "✓ Page ${vm.pages.size + vm.pending} captured. Show the next page"
                            liveCorners == null -> "Place the document in view"
                            vm.autoCapture -> "Hold steady…"
                            else -> "Tap the shutter"
                        },
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
                Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
            }
        }

        // bottom controls
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                IconButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Import photos", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Box(
                Modifier
                    .size(78.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        detector.manualCaptureAt = SystemClock.elapsedRealtime()
                        takePhoto()
                    }
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.pending > 0) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Box(Modifier.width(8.dp))
                    }
                    Button(onClick = onDone, enabled = vm.pages.isNotEmpty()) {
                        Text(if (vm.pages.isEmpty()) "Done" else "Done (${vm.pages.size})")
                    }
                }
            }
        }
    }
}
