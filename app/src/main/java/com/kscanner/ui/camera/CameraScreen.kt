package com.kscanner.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Flip
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.Image
import com.kscanner.core.imaging.CaptureAdvice
import com.kscanner.core.imaging.CaptureGate
import com.kscanner.core.imaging.OutlineTracker
import com.kscanner.core.imaging.Quad
import com.kscanner.data.ScanMode
import com.kscanner.ui.capture.ReviewState
import com.kscanner.ui.capture.ScanSessionViewModel
import com.kscanner.ui.components.rememberFileImage
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import com.kscanner.KScannerApp

/**
 * Camera capture built on CameraX. A capture is written straight to a temporary file and either
 * handed to the editor by path, or processed into a document in place; no bitmap ever crosses
 * the activity boundary either way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(
    documentId: Long?,
    initialMode: ScanMode,
    initialBatch: Boolean,
    session: ScanSessionViewModel,
    onCaptured: (String) -> Unit,
    onFinished: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as KScannerApp
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        session.prepareIfNeeded(initialMode, initialBatch, documentId)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    // Whether the system would still put the dialog up. This is *not* the question
    // `permissionRequested` answers: after a permanent denial it stays false for good, which is
    // the one case where the only way out of this screen is the app's page in Settings.
    var canAskAgain by remember { mutableStateOf(true) }

    fun readPermissionState() {
        hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        canAskAgain = hasPermission || (context as? Activity)?.let { activity ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        } ?: true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        permissionRequested = true
        readPermissionState()
    }

    // Coming back from Settings must start the preview, not leave this screen up as a dead end.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) readPermissionState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        PermissionScreen(
            denied = permissionRequested,
            permanentDenial = permissionRequested && !canAskAgain,
            onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { openAppSettings(context) },
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

    // The live outline. Detection runs on the analysis thread, drawing happens here on the main
    // thread, and the tracker is the memory in between. Five density-independent pixels of
    // deadband is the same figure the competing scanners use: it is the point at which a hand's
    // tremor stops looking like the page moving.
    val density = LocalDensity.current
    val outlineTracker = remember(density) {
        OutlineTracker(deadbandPx = with(density) { 5.dp.toPx().toDouble() })
    }
    var outline by remember { mutableStateOf<Quad?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

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
                    if (session.capturesToEditor) {
                        onCaptured(file.absolutePath)
                    } else {
                        session.consume(file.absolutePath) {
                            // Only pages that were actually added get here — a re-take adds
                            // none — so with Batch off, the shot that completes the unit is
                            // the one that hands over to the document.
                            if (!session.batch) session.documentId?.let(onFinished)
                        }
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

    LaunchedEffect(outlineTracker) {
        // The outline on screen is a tween, so it is read once per frame rather than once per
        // detection. Assigning an unchanged Quad costs nothing: the state ignores equal values.
        while (true) {
            withFrameNanos { }
            outline = outlineTracker.sample(SystemClock.elapsedRealtime())
        }
    }

    LaunchedEffect(cameraProvider, previewView) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // Analysis stays bound whether or not the automatic shutter is on: the framing overlay
        // needs the same frames, and unbinding to flip a toggle would restart the preview.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))
            .build()
            .also { useCase ->
                useCase.setAnalyzer(
                    analysisExecutor,
                    AutoCaptureAnalyzer(
                        gate = gate,
                        tracker = outlineTracker,
                        onAdvice = { next, progress ->
                            mainHandler.post {
                                advice = next
                                readiness = progress
                            }
                        },
                        onFire = { if (autoCapture) mainHandler.post { shoot() } },
                        onCandidates = { found, frame ->
                            mainHandler.post {
                                outlineTracker.accept(
                                    candidates = found,
                                    frame = frame,
                                    viewWidth = previewSize.width,
                                    viewHeight = previewSize.height,
                                    now = SystemClock.elapsedRealtime(),
                                )
                            }
                        },
                    ),
                )
            }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            provider.unbindAll()
            val useCases = arrayOf<androidx.camera.core.UseCase>(preview, capture, analysis)
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

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { previewSize = it }) {
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

        // The outline the detector is following, drawn where the preview actually put the frame.
        FramingOverlay(outline, Modifier.fillMaxSize())

        // Book mode cuts at the gutter, so the gutter has to be alignable before the shot. Only in
        // Book mode, and under the controls: the line is a reference, not a control.
        if (session.mode == ScanMode.BOOK) {
            BookGuide(Modifier.fillMaxSize())
        }

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

        // Top controls. The preview stays full bleed behind the status bar; only the strip that
        // carries controls is inset, so nothing is drawn under the clock and the cutout.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
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

            // What the next shot becomes (mode), and whether the run goes on after it (Batch).
            // Two different questions, so they are two different controls: Batch is a toggle, and
            // it is coloured differently from the mode chips so it does not read as a fourth mode.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (option in ScanMode.entries) {
                    ModeChip(
                        mode = option,
                        active = session.mode == option,
                        onClick = { session.updateMode(option) },
                    )
                }
                BatchChip(
                    active = session.batch,
                    onToggle = { session.updateBatch(it) },
                )
            }
        }

        // Bottom controls, lifted clear of the navigation bar: the shutter is the one control that
        // must not share its strip with the system's, or a tap lands on Home instead of Capture.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
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
                    append(runHint(session.mode, session.batch))
                    if (documentId != null) append("  ·  into the open document")
                },
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Page strip for the runs that write pages directly: a glance at what has landed so
            // far, and the way into the review. Newest commit first, and within a commit in
            // reading order, so a tile carries its own page number rather than counting back
            // from the front.
            if (!session.capturesToEditor && session.recent.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    items(session.recent) { tile ->
                        val number = tile.number
                        val total = session.pageCount
                        Image(
                            bitmap = tile.bitmap,
                            // Fit, not Crop: a tile must show the whole page, not a cut-out of it.
                            contentDescription = "Page $number of $total",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(48.dp, 64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(
                                    onClickLabel = "Open page $number of $total",
                                ) { session.openReview(tile.pageId) },
                        )
                    }
                }
            }

            // An armed re-take changes what the next shot does, so it is stated on screen with
            // a way out: the shot replaces that one page, and the run carries on from there.
            session.retakePosition?.let { position ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 2.dp),
                    ) {
                        Text(
                            text = "Re-take page $position",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        IconButton(
                            onClick = session::cancelRetake,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Cancel re-take",
                                modifier = Modifier.size(16.dp),
                            )
                        }
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

            if (!session.capturesToEditor) {
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

        // The review is an overlay on this screen, not a route of its own: leaving would
        // re-run the init effect above and wipe the run in progress.
        session.review?.let { state ->
            ReviewOverlay(
                state = state,
                error = session.error,
                canRetake = session.canRetake,
                onClose = session::closeReview,
                onStep = session::stepReview,
                onRotate = { session.rotateReviewPage(clockwise = true) },
                onFlip = session::flipReviewPage,
                onRetake = session::retakeReviewPage,
            )
        }
    }
}

/**
 * The full-bleed review of a run: what was committed, page by page, with the rotate and
 * flip the automatic pass may have got wrong, and a way to shoot one page again where a single
 * shot is a single page. Reads the stored rows through [ScanSessionViewModel.review], so it can
 * reach page 1 of a run longer than the strip.
 */
@Composable
private fun ReviewOverlay(
    state: ReviewState,
    error: String?,
    canRetake: Boolean,
    onClose: () -> Unit,
    onStep: (Int) -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onRetake: () -> Unit,
) {
    val page = state.current
    val image = rememberFileImage(page?.processedPath, maxEdge = 1800, revision = page?.renderRevision)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = page?.let { "Page ${state.index + 1} of ${state.pages.size}" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // The strips above and below carry the inset, so they move in by the height
                    // of the system bars; the page follows them in, or the icons would be drawn
                    // over the page and a white icon on white paper cannot be seen.
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = 88.dp),
            )
        } else {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        }

        // The review is drawn over the capture screen, so it inherits none of that screen's
        // insets and must bring its own: the top strip would otherwise be under the clock, and
        // the bottom strip — where Re-take sits — under the navigation bar, which takes its taps.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close review",
                    tint = Color.White,
                )
            }
            Text(
                text = state.position,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRotate) {
                Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = "Rotate this page", tint = Color.White)
            }
            IconButton(onClick = onFlip) {
                Icon(Icons.Outlined.Flip, contentDescription = "Turn this page over", tint = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Only `false` earns this: null means no plan ran, and accusing such a page of
            // being undecided would be inventing doubt the app never had.
            if (page?.flipDecided == false) {
                Surface(
                    onClick = onFlip,
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                    modifier = Modifier.padding(bottom = 14.dp),
                ) {
                    Text(
                        text = "Upside down? The automatic pass could not tell",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onStep(-1) },
                    enabled = state.index > 0,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous page",
                        tint = if (state.index > 0) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                // Re-shoot this page where it sits, rather than deleting it and dragging the
                // new one back from the end. Offered only where one shot means one page.
                if (canRetake) {
                    Surface(
                        onClick = onRetake,
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Re-take", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                IconButton(
                    onClick = { onStep(1) },
                    enabled = state.index < state.pages.lastIndex,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next page",
                        tint = if (state.index < state.pages.lastIndex) Color.White
                        else Color.White.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}

/**
 * What the run will do next, in the mode's own terms.
 *
 * The one-page shape says something different with Batch on: the shot no longer goes to the
 * editor, so repeating it is what builds the document.
 */
private fun runHint(mode: ScanMode, batch: Boolean): String = when {
    mode == ScanMode.SINGLE && batch -> "Page after page"
    else -> mode.hint
}

@Composable
private fun ModeChip(mode: ScanMode, active: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.selectable(
            selected = active,
            role = Role.RadioButton,
            onClick = onClick,
        ),
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

/**
 * The Batch toggle: keep shooting into the document, or stop after one unit.
 *
 * Not a mode, so it does not look like one — a mode chip lights up white, and this looks like
 * what it is, a switch that is on. It *is* one to TalkBack: `toggleable` with `Role.Switch` is
 * what makes the state audible, since the colour is the only other thing that carries it.
 */
@Composable
private fun BatchChip(active: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.toggleable(
            value = active,
            role = Role.Switch,
            onValueChange = onToggle,
        ),
        shape = RoundedCornerShape(16.dp),
        color = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.42f),
        contentColor = Color.White,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = if (active) Icons.Outlined.CheckCircleOutline else Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(text = "Batch", style = MaterialTheme.typography.labelSmall)
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
private fun PermissionScreen(
    denied: Boolean,
    permanentDenial: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
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
            text = when {
                permanentDenial ->
                    "Camera access is blocked, so KScanner cannot ask again. Allow the camera in the app's settings, or import pages from your gallery instead."
                denied ->
                    "KScanner needs the camera to scan documents. You can grant it in Settings, or import pages from your gallery instead."
                else ->
                    "KScanner uses the camera to photograph documents for scanning."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        // One primary action, whichever route can still work: the OS dialog while it will still
        // appear, and the app's settings page once it has stopped appearing for good.
        if (permanentDenial) {
            Button(onClick = onOpenSettings) { Text("Open settings") }
        } else {
            Button(onClick = onGrant) { Text("Allow camera") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.textButtonColors(),
        ) { Text("Not now") }
    }
}

/** The app's own page in system Settings: the only route back from a permanent denial. */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(intent) }
}
