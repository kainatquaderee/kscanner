package com.kscanner.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import com.kscanner.core.imaging.CaptureAdvice
import com.kscanner.core.imaging.CaptureGate
import com.kscanner.data.ScanMode
import com.kscanner.ui.capture.ScanSessionViewModel
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import com.kscanner.KScannerApp

/**
 * Camera capture built on CameraX. Captures are written straight to a temporary file and
 * handed to the editor, so no bitmap ever crosses the activity boundary.
 */
@Composable
fun CameraScreen(
    documentId: Long?,
    initialMode: ScanMode,
    session: ScanSessionViewModel,
    onCaptured: (String) -> Unit,
    onFinished: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as KScannerApp
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        session.prepare(initialMode)
        session.beginInto(documentId)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        PermissionScreen(
            denied = permissionRequested,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onClose = onClose,
        )
        return
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(1f) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    var autoCapture by remember { mutableStateOf(app.container.settings.current.autoCapture) }
    var advice by remember { mutableStateOf(CaptureAdvice.GOOD) }
    var readiness by remember { mutableStateOf(0.0) }
    val gate = remember { CaptureGate() }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    /** Fires the shutter once. Shared by the button and the automatic gate. */
    fun shoot() {
        if (capturing) return
        val capture = imageCapture ?: return
        capturing = true
        error = null
        gate.reset()
        readiness = 0.0
        val file = app.container.storage.newCaptureFile()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    capturing = false
                    if (session.mode.isContinuous) {
                        session.consume(file.absolutePath)
                    } else {
                        onCaptured(file.absolutePath)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    error = "Capture failed: ${exception.message}"
                }
            },
        )
    }

    DisposableEffect(Unit) {
        val listener = Runnable { cameraProvider = runCatching { providerFuture.get() }.getOrNull() }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(cameraProvider, previewView, autoCapture) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // Analysis stays bound only in automatic mode: there is no reason to burn battery
        // measuring frames nobody is going to act on.
        val analysis = if (autoCapture) {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(
                        analysisExecutor,
                        AutoCaptureAnalyzer(
                            gate = gate,
                            onAdvice = { next, progress ->
                                mainHandler.post {
                                    advice = next
                                    readiness = progress
                                }
                            },
                            onFire = { mainHandler.post { shoot() } },
                        ),
                    )
                }
        } else {
            null
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            provider.unbindAll()
            val useCases = if (analysis != null) {
                arrayOf<androidx.camera.core.UseCase>(preview, capture, analysis)
            } else {
                arrayOf<androidx.camera.core.UseCase>(preview, capture)
            }
            val bound = provider.bindToLifecycle(lifecycleOwner, selector, *useCases)
            camera = bound
            imageCapture = capture
            maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            bound.cameraControl.enableTorch(torchOn)
        }.onFailure {
            error = "The camera could not be started on this device."
        }
    }

    DisposableEffect(torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
        onDispose { }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (showGrid) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 1.2f)
                val colour = Color.White.copy(alpha = 0.28f)
                for (i in 1..2) {
                    val x = size.width * i / 3f
                    val y = size.height * i / 3f
                    drawLine(colour, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = stroke.width)
                    drawLine(colour, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = stroke.width)
                }
            }
        }

        // Top controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showGrid = !showGrid }) {
                Icon(
                    imageVector = if (showGrid) Icons.Outlined.GridOn else Icons.Outlined.GridOff,
                    contentDescription = "Toggle grid",
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = {
                    autoCapture = !autoCapture
                    gate.reset()
                    readiness = 0.0
                    app.container.settings.update { it.copy(autoCapture = autoCapture) }
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = if (autoCapture) "Automatic shutter on" else "Automatic shutter off",
                    tint = if (autoCapture) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(onClick = { torchOn = !torchOn }) {
                Icon(
                    imageVector = if (torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = "Toggle flash",
                    tint = if (torchOn) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
        }

        // Mode selector: what each shot becomes.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (option in ScanMode.entries) {
                ModeChip(
                    mode = option,
                    active = session.mode == option,
                    onClick = { session.updateMode(option) },
                )
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (maxZoom > 1f) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 14.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZoomChip("1x", zoomRatio < 1.5f) {
                        zoomRatio = 1f
                        camera?.cameraControl?.setZoomRatio(1f)
                    }
                    ZoomChip("2x", zoomRatio in 1.5f..2.5f) {
                        zoomRatio = 2f
                        camera?.cameraControl?.setZoomRatio(2f.coerceAtMost(maxZoom))
                    }
                    if (maxZoom >= 4f) {
                        ZoomChip("4x", zoomRatio > 2.5f) {
                            zoomRatio = 4f
                            camera?.cameraControl?.setZoomRatio(4f.coerceAtMost(maxZoom))
                        }
                    }
                }
            }

            Text(
                text = buildString {
                    append(session.mode.hint)
                    if (documentId != null) append("  ·  into the open document")
                },
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Page strip for the continuous modes: a glance at what has landed so far.
            if (session.mode.isContinuous && session.recent.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    items(session.recent) { thumb ->
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }

            session.status?.let { message ->
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            val scale by animateFloatAsState(if (capturing) 0.86f else 1f, label = "shutter")

            Surface(
                onClick = { shoot() },
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.16f),
                border = androidx.compose.foundation.BorderStroke(4.dp, Color.White),
            ) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    if (capturing) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    } else if (autoCapture) {
                        // A ring that fills as the frame settles, so the wait is legible.
                        CircularProgressIndicator(
                            progress = { readiness.toFloat() },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.35f),
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(52.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.PhotoCamera,
                            contentDescription = "Capture",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }

            if (autoCapture) {
                Text(
                    text = advice.message,
                    color = if (advice == CaptureAdvice.GOOD) Color.White.copy(alpha = 0.9f)
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            (error ?: session.error)?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            if (session.mode.isContinuous) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { session.documentId?.let(onFinished) ?: onClose() },
                    enabled = session.pageCount > 0 && !session.busy,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (session.pageCount == 0) {
                            "Done"
                        } else {
                            "Done  ·  ${session.pageCount} ${if (session.pageCount == 1) "page" else "pages"}"
                        },
                    )
                }
                if (session.busy) {
                    Text(
                        text = "Adding the page...",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: ScanMode, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (active) Color.White else Color.Black.copy(alpha = 0.42f),
        contentColor = if (active) Color.Black else Color.White,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = when (mode) {
                    ScanMode.SINGLE -> Icons.Outlined.Description
                    ScanMode.BATCH -> Icons.Outlined.PhotoLibrary
                    ScanMode.BOOK -> Icons.AutoMirrored.Outlined.MenuBook
                    ScanMode.ID_CARD -> Icons.Outlined.CreditCard
                },
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(text = mode.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ZoomChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) Color.White else Color.Transparent,
        contentColor = if (active) Color.Black else Color.White,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onRequest: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Camera access is needed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (denied) {
                "KScanner needs the camera to scan documents. You can grant it in Settings, or import pages from your gallery instead."
            } else {
                "KScanner uses the camera to photograph documents for scanning."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.textButtonColors(),
        ) { Text("Not now") }
    }
}
