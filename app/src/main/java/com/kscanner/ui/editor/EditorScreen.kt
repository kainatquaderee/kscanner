package com.kscanner.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.kscanner.core.imaging.CaptureAdvice
import com.kscanner.core.imaging.DetectMethod
import com.kscanner.core.imaging.Pt
import com.kscanner.core.imaging.Quad
import com.kscanner.core.imaging.ScanFilter
import com.kscanner.ui.EditorViewModel
import com.kscanner.ui.components.LoadingOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    path: String,
    documentId: Long?,
    onSaved: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    LaunchedEffect(path) { viewModel.load(path) }

    val snackbarHostState = remember { SnackbarHostState() }
    var adjustSheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(viewModel.error) {
        val message = viewModel.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.stage == EditorViewModel.Stage.CROP) "Adjust boundaries" else "Choose a filter",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (viewModel.stage == EditorViewModel.Stage.CROP) onCancel()
                            else viewModel.backToCropStage()
                        },
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (viewModel.stage == EditorViewModel.Stage.FILTER) {
                        IconButton(onClick = { adjustSheetOpen = true }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Adjust")
                        }
                        IconButton(onClick = { viewModel.resetAdjustments() }) {
                            Icon(Icons.Outlined.AutoFixHigh, contentDescription = "Reset adjustments")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            when (viewModel.stage) {
                EditorViewModel.Stage.CROP -> CropBar(
                    onRotateLeft = viewModel::rotateCounterClockwise,
                    onRotateRight = viewModel::rotateClockwise,
                    onAutoDetect = viewModel::autoDetect,
                    onReset = viewModel::resetQuadToFrame,
                    onDeskew = viewModel::autoDeskew,
                    onClearDeskew = viewModel::clearDeskew,
                    deskewActive = viewModel.deskewDegrees != 0.0,
                    deskewDegrees = viewModel.deskewDegrees,
                    onNext = viewModel::openFilterStage,
                    enabled = viewModel.previewBitmap != null && !viewModel.loading,
                )

                EditorViewModel.Stage.FILTER -> FilterBar(
                    selected = viewModel.filter,
                    onSelect = viewModel::selectFilter,
                    strength = viewModel.strength,
                    onStrength = viewModel::updateStrength,
                    saving = viewModel.saving,
                    onAutoEnhance = viewModel::autoEnhance,
                    onCrop = viewModel::backToCropStage,
                    onSave = { viewModel.save(documentId, onSaved) },
                )
            }
        },
    ) { padding ->
        LoadingOverlay(
            visible = viewModel.loading || viewModel.saving,
            message = if (viewModel.saving) "Saving page…" else "Preparing…",
            modifier = Modifier.padding(padding),
        ) {
            when (viewModel.stage) {
                EditorViewModel.Stage.CROP -> {
                    val bitmap = viewModel.previewBitmap
                    if (bitmap != null) {
                        CropStage(
                            bitmap = bitmap,
                            quad = viewModel.quadInPreview(bitmap),
                            onMoveCorner = viewModel::moveCorner,
                            autoDetected = viewModel.autoDetected,
                            statusText = cropStatus(viewModel),
                        )
                    } else {
                        EmptyEditor()
                    }
                }

                EditorViewModel.Stage.FILTER -> {
                    val bitmap = viewModel.resultBitmap
                    if (bitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Filter preview",
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    } else {
                        EmptyEditor()
                    }
                }
            }
        }
    }

    if (adjustSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { adjustSheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Adjust", style = MaterialTheme.typography.titleMedium)
                AdjustmentSlider(
                    label = "Brightness",
                    value = viewModel.brightness,
                    range = -1f..1f,
                    onChange = viewModel::updateBrightness,
                )
                AdjustmentSlider(
                    label = "Contrast",
                    value = viewModel.contrast,
                    range = -1f..1f,
                    onChange = viewModel::updateContrast,
                )
                AdjustmentSlider(
                    label = "Sharpness",
                    value = viewModel.sharpness,
                    range = 0f..1f,
                    onChange = viewModel::updateSharpness,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = viewModel::resetAdjustments) { Text("Reset") }
                    TextButton(onClick = { adjustSheetOpen = false }) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (range.start < 0f) "${(value * 100).toInt()}" else "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun EmptyEditor() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text("Nothing to edit", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ crop stage

/**
 * One line explaining what the automatic pass decided, so the crop stage is never a black
 * box: which cue found the outline, how sure it is, and what is wrong with the shot when
 * something is.
 */
private fun cropStatus(viewModel: EditorViewModel): String {
    val advice = viewModel.advice
    if (advice != CaptureAdvice.GOOD) return advice.message

    val method = viewModel.detectionMethod
        ?: return "Outline not detected — drag the corners onto the page"

    val confidence = when {
        viewModel.detectionConfidence >= 0.75 -> "high confidence"
        viewModel.detectionConfidence >= 0.45 -> "medium confidence"
        else -> "low confidence"
    }
    val source = when (method) {
        DetectMethod.EDGES -> "page edges"
        DetectMethod.BRIGHT_REGION -> "the bright sheet"
        DetectMethod.TEXT_BLOCK -> "the text block"
    }
    return "Outline from $source, $confidence — drag a corner to fine-tune"
}

@Composable
private fun CropStage(
    bitmap: ImageBitmap,
    quad: Quad,
    onMoveCorner: (Int, Double, Double) -> Unit,
    autoDetected: Boolean,
    statusText: String,
) {
    val accent = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            val canvasWidth = constraints.maxWidth.toFloat()
            val canvasHeight = constraints.maxHeight.toFloat()
            val scale = minOf(canvasWidth / bitmap.width, canvasHeight / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (canvasWidth - drawWidth) / 2f
            val top = (canvasHeight - drawHeight) / 2f
            val mapping = ImageMapping(left, top, scale, bitmap.width, bitmap.height)

            val latestMapping by rememberUpdatedState(mapping)
            val latestQuad by rememberUpdatedState(quad)
            val latestMove by rememberUpdatedState(onMoveCorner)
            var activeCorner by remember { mutableStateOf(-1) }
            val touchRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { position ->
                                activeCorner = latestMapping.nearestCorner(latestQuad, position, touchRadius)
                            },
                            onDragEnd = { activeCorner = -1 },
                            onDragCancel = { activeCorner = -1 },
                            onDrag = { change, _ ->
                                val index = activeCorner
                                if (index >= 0) {
                                    val normalised = latestMapping.toNormalised(change.position)
                                    latestMove(index, normalised.x.toDouble(), normalised.y.toDouble())
                                    change.consume()
                                }
                            },
                        )
                    },
            ) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt()),
                )

                val tl = quad.tl.toCanvas(mapping)
                val tr = quad.tr.toCanvas(mapping)
                val br = quad.br.toCanvas(mapping)
                val bl = quad.bl.toCanvas(mapping)

                // Dim everything outside the outline without needing a blend mode.
                val scrim = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                    moveTo(tl.x, tl.y)
                    lineTo(tr.x, tr.y)
                    lineTo(br.x, br.y)
                    lineTo(bl.x, bl.y)
                    close()
                    fillType = PathFillType.EvenOdd
                }
                drawPath(scrim, Color.Black.copy(alpha = 0.55f))

                val outline = Path().apply {
                    moveTo(tl.x, tl.y)
                    lineTo(tr.x, tr.y)
                    lineTo(br.x, br.y)
                    lineTo(bl.x, bl.y)
                    close()
                }
                drawPath(outline, accent, style = Stroke(width = 2.5.dp.toPx()))

                for ((index, point) in listOf(tl, tr, br, bl).withIndex()) {
                    val radius = if (index == activeCorner) 13.dp.toPx() else 10.dp.toPx()
                    drawCircle(Color.White, radius = radius, center = point)
                    drawCircle(
                        color = if (index == activeCorner) accent else accent.copy(alpha = 0.9f),
                        radius = radius,
                        center = point,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CropFree,
                    contentDescription = null,
                    tint = if (autoDetected) accent else error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CropBar(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onAutoDetect: () -> Unit,
    onReset: () -> Unit,
    onDeskew: () -> Unit,
    onClearDeskew: () -> Unit,
    deskewActive: Boolean,
    deskewDegrees: Double,
    onNext: () -> Unit,
    enabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRotateLeft) {
                    Icon(Icons.Outlined.RotateLeft, contentDescription = "Rotate left")
                }
                IconButton(onClick = onRotateRight) {
                    Icon(Icons.Outlined.RotateRight, contentDescription = "Rotate right")
                }
                IconButton(onClick = onAutoDetect) {
                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = "Detect automatically")
                }
                IconButton(onClick = onReset) {
                    Icon(Icons.Outlined.CropFree, contentDescription = "Reset outline")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onNext, enabled = enabled) {
                    Text("Next")
                }
            }

            // Level the text. The measurement runs on the cropped page, so this is a second
            // chance to straighten a page whose outline was already right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (deskewActive) {
                    TextButton(onClick = onClearDeskew) {
                        Text("Undo straighten")
                    }
                    Text(
                        text = "${if (deskewDegrees > 0) "+" else ""}${
                            String.format("%.1f", deskewDegrees)
                        }°",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else {
                    TextButton(onClick = onDeskew) { Text("Straighten text") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ filter stage

@Composable
private fun FilterBar(
    selected: ScanFilter,
    onSelect: (ScanFilter) -> Unit,
    strength: Float,
    onStrength: (Float) -> Unit,
    saving: Boolean,
    onAutoEnhance: () -> Unit,
    onCrop: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ScanFilter.entries.toList(), key = { it.id }) { filter ->
                    FilterChip(
                        selected = filter == selected,
                        onClick = { onSelect(filter) },
                        label = { Text(filter.label) },
                        leadingIcon = if (filter == selected) {
                            { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            if (selected != ScanFilter.ORIGINAL) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Strength", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = strength,
                        onValueChange = onStrength,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text(
                        text = "${(strength * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCrop) { Text("Crop") }
                TextButton(onClick = onAutoEnhance) { Text("Auto") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSave, enabled = !saving) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save page")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ mapping

private data class ImageMapping(
    val left: Float,
    val top: Float,
    val scale: Float,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    fun toNormalised(position: Offset): Offset = Offset(
        ((position.x - left) / scale / imageWidth).coerceIn(0f, 1f),
        ((position.y - top) / scale / imageHeight).coerceIn(0f, 1f),
    )

    fun nearestCorner(quad: Quad, position: Offset, radius: Float): Int {
        var bestIndex = -1
        var bestDistance = radius
        quad.points.forEachIndexed { index, point ->
            val canvas = point.toCanvas(this)
            val distance = (canvas - position).getDistance()
            if (distance <= bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }
}

private fun Pt.toCanvas(mapping: ImageMapping): Offset =
    Offset(mapping.left + (x * mapping.scale).toFloat(), mapping.top + (y * mapping.scale).toFloat())

private fun Float.coerceIn(min: Float, max: Float): Float = if (this < min) min else if (this > max) max else this

private fun abs(value: Float): Float = if (value < 0f) -value else value