package com.kscanner.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kscanner.core.imaging.AutoCrop
import com.kscanner.core.imaging.BitmapBridge
import com.kscanner.core.imaging.CaptureAdvice
import com.kscanner.core.imaging.DetectMethod
import com.kscanner.core.imaging.DocDetector
import com.kscanner.core.imaging.Filters
import com.kscanner.core.imaging.Quad
import com.kscanner.core.imaging.RgbaImage
import com.kscanner.core.imaging.ScanFilter
import com.kscanner.core.imaging.ScanProcessor
import com.kscanner.core.imaging.Skew
import com.kscanner.core.imaging.Transforms
import com.kscanner.data.ScanRepository
import com.kscanner.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the two-stage editor: correct the outline, then choose the look.
 *
 * The outline is held in normalised 0..1 coordinates of the *rotated* source image. That
 * keeps it independent of whatever resolution the preview happens to be rendered at, so a
 * crop corrected on a 1080px preview applies exactly to the full-resolution original.
 */
class EditorViewModel(
    private val repository: ScanRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    enum class Stage { CROP, FILTER }

    var stage by mutableStateOf(Stage.CROP)
        private set

    var loading by mutableStateOf(true)
        private set

    var saving by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Preview of the rotated source, used by the crop stage. */
    var previewBitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Preview of the finished page, used by the filter stage. */
    var resultBitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    /** The outline in normalised coordinates of the rotated image. */
    var quadNorm by mutableStateOf(Quad.rect(1.0, 1.0))
        private set

    var rotation by mutableStateOf(0)
        private set

    var filter by mutableStateOf(settingsStore.defaultFilter)
        private set

    var strength by mutableStateOf(1f)
        private set

    var brightness by mutableStateOf(0f)
        private set

    var contrast by mutableStateOf(0f)
        private set

    var sharpness by mutableStateOf(0f)
        private set

    /** True when the current outline came from automatic detection. */
    var autoDetected by mutableStateOf(false)
        private set

    /** Residual text skew of the cropped page, in degrees (clockwise positive). */
    var deskewDegrees by mutableStateOf(0.0)
        private set

    /** How the outline was found, for the status line. Null when it was not detected. */
    var detectionMethod by mutableStateOf<DetectMethod?>(null)
        private set

    var detectionConfidence by mutableStateOf(0.0)
        private set

    /** What the capture's own measurements say about it. */
    var advice by mutableStateOf(CaptureAdvice.GOOD)
        private set

    /** True while an automatic pass is running, so the UI can show a progress hint. */
    var analysing by mutableStateOf(false)
        private set

    private var original: RgbaImage? = null
    private var previewImage: RgbaImage? = null
    private var sourcePath: String? = null
    private var previewJob: Job? = null

    val hasImage: Boolean get() = original != null

    fun load(path: String) {
        if (sourcePath == path && original != null) return
        sourcePath = path
        loading = true
        analysing = true
        error = null
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.Default) {
                BitmapBridge.decodeFile(File(path), 2400)
            }
            if (decoded == null) {
                error = "That image could not be opened."
                loading = false
                analysing = false
                return@launch
            }
            original = decoded
            rotation = 0
            filter = settingsStore.defaultFilter
            strength = 1f
            brightness = 0f
            contrast = 0f
            sharpness = 0f
            runAutoPass(applyStyle = true)
            loading = false
            analysing = false
        }
    }

    // ------------------------------------------------------------ automatic pass

    /**
     * Runs the whole automatic preparation on the untouched capture: make it upright, find
     * the outline, measure the residual skew and the frame quality. When [applyStyle] is
     * set the enhancement preset is also chosen from the frame's own measurements.
     */
    private suspend fun runAutoPass(applyStyle: Boolean) {
        val source = original ?: return
        val prepared = withContext(Dispatchers.Default) {
            val plan = AutoCrop.plan(
                source,
                workingEdge = 1080,
                fixOrientation = settingsStore.current.autoRotate,
            )
            val rotated = Transforms.rotate(source, plan.rotationDegrees)
            val rotatedQuad = plan.quad
            val preview = Transforms.fitLongestEdge(rotated, 1080)
            if (applyStyle && settingsStore.current.autoFilter) {
                val upright = Transforms.fitLongestEdge(rotated, 1080)
                filter = Filters.auto(upright)
            }
            Prepared(plan, rotatedQuad, preview)
        }

        rotation = prepared.plan.rotationDegrees
        deskewDegrees = if (settingsStore.current.autoDeskew) prepared.plan.deskewDegrees else 0.0
        detectionMethod = prepared.plan.method
        detectionConfidence = prepared.plan.confidence
        advice = prepared.plan.quality.advice
        previewImage = prepared.preview

        val quad = prepared.quad
        quadNorm = if (quad != null) {
            quad.scaled(1.0 / prepared.preview.width, 1.0 / prepared.preview.height)
        } else {
            Quad.rect(1.0, 1.0).insetBy(0.025)
        }
        autoDetected = quad != null

        previewBitmap = withContext(Dispatchers.Default) {
            BitmapBridge.toBitmap(prepared.preview).asImageBitmap()
        }
    }

    private class Prepared(
        val plan: AutoCrop.Plan,
        val quad: Quad?,
        val preview: RgbaImage,
    )

    // ------------------------------------------------------------ crop stage

    fun rotateClockwise() = rotateQuarter()

    fun rotateCounterClockwise() = rotateQuarter(clockwise = false)

    private fun rotateQuarter(clockwise: Boolean = true) {
        if (original == null) return
        val currentQuad = quadNorm
        viewModelScope.launch {
            loading = true
            val nextRotation = if (clockwise) (rotation + 90) % 360 else (rotation + 270) % 360

            // Rotate the outline with the pixels so it still describes the same physical edge.
            val movedQuad = if (clockwise) {
                currentQuad.rotated90Normalized()
            } else {
                currentQuad.rotated270Normalized()
            }

            rotation = nextRotation
            quadNorm = movedQuad
            refreshPreview(detect = false, remeasureSkew = true)
            loading = false
        }
    }

    /** Re-runs detection and the orientation decision on the current capture. */
    fun autoDetect() {
        if (original == null) return
        viewModelScope.launch {
            loading = true
            analysing = true
            runAutoPass(applyStyle = false)
            analysing = false
            loading = false
        }
    }

    /** Chooses the enhancement preset from the page's own measurements. */
    fun autoEnhance() {
        val source = original ?: return
        viewModelScope.launch {
            val chosen = withContext(Dispatchers.Default) {
                Filters.auto(Transforms.fitLongestEdge(Transforms.rotate(source, rotation), 1080))
            }
            filter = chosen
            if (stage == Stage.FILTER) recomputeResult()
        }
    }

    /** Re-measures the residual skew of the cropped page and applies it. */
    fun autoDeskew() {
        if (original == null) return
        viewModelScope.launch {
            loading = true
            val measured = withContext(Dispatchers.Default) { measureSkew() }
            deskewDegrees = measured
            if (stage == Stage.FILTER) recomputeResult()
            loading = false
        }
    }

    fun clearDeskew() {
        deskewDegrees = 0.0
        if (stage == Stage.FILTER) recomputeResult()
    }

    fun resetQuadToFrame() {
        quadNorm = Quad.rect(1.0, 1.0).insetBy(0.02)
        autoDetected = false
    }

    /** Moves one corner of the outline; rejects moves that would fold the quadrilateral. */
    fun moveCorner(index: Int, xNorm: Double, yNorm: Double) {
        val clampedX = xNorm.coerceIn(0.0, 1.0)
        val clampedY = yNorm.coerceIn(0.0, 1.0)
        val points = quadNorm.points.toMutableList()
        points[index] = com.kscanner.core.imaging.Pt(clampedX, clampedY)
        val candidate = Quad(points[0], points[1], points[2], points[3])
        if (!candidate.isConvex()) return
        if (candidate.area() < 0.01) return
        quadNorm = candidate
        autoDetected = false
    }

    // ------------------------------------------------------------ filter stage

    fun openFilterStage() {
        stage = Stage.FILTER
        recomputeResult(immediate = true)
    }

    fun backToCropStage() {
        stage = Stage.CROP
        previewJob?.cancel()
    }

    fun selectFilter(next: ScanFilter) {
        filter = next
        recomputeResult()
    }

    fun updateStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
        recomputeResult()
    }

    fun updateBrightness(value: Float) {
        brightness = value.coerceIn(-1f, 1f)
        recomputeResult()
    }

    fun updateContrast(value: Float) {
        contrast = value.coerceIn(-1f, 1f)
        recomputeResult()
    }

    fun updateSharpness(value: Float) {
        sharpness = value.coerceIn(0f, 1f)
        recomputeResult()
    }

    fun resetAdjustments() {
        brightness = 0f
        contrast = 0f
        sharpness = 0f
        strength = 1f
        recomputeResult()
    }

    private fun recomputeResult(immediate: Boolean = false) {
        val source = original ?: return
        val settings = buildSettings(source)
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(110)
            val rendered = withContext(Dispatchers.Default) {
                ScanProcessor.preview(source, settings, previewEdge = 1080)
            }
            resultBitmap = withContext(Dispatchers.Default) {
                BitmapBridge.toBitmap(rendered).asImageBitmap()
            }
        }
    }

    // ------------------------------------------------------------ shared

    /**
     * Rebuilds the preview after the user turns the picture. Detection is skipped (the
     * outline travels with the pixels), but the skew is re-measured because a quarter turn
     * changes which way the text leans.
     */
    private suspend fun refreshPreview(detect: Boolean, remeasureSkew: Boolean = false) {
        val source = original ?: return
        val rendered = withContext(Dispatchers.Default) {
            val rotated = Transforms.rotate(source, rotation)
            val preview = Transforms.fitLongestEdge(rotated, 1080)
            val detected = if (detect) {
                DocDetector.detect(preview)?.quad
            } else {
                null
            }
            val skew = if (remeasureSkew) measureSkew() else null
            Triple(preview, detected, skew)
        }
        val (preview, found, skew) = rendered
        previewImage = preview

        if (detect) {
            quadNorm = found?.scaled(1.0 / preview.width, 1.0 / preview.height)
                ?: Quad.rect(1.0, 1.0).insetBy(0.025)
            autoDetected = found != null
        }
        if (skew != null) deskewDegrees = skew

        previewBitmap = withContext(Dispatchers.Default) {
            BitmapBridge.toBitmap(preview).asImageBitmap()
        }
    }

    /**
     * Skew of the text inside the current outline. Measuring it on the cropped page rather
     * than the whole frame keeps background edges out of the vote.
     */
    private fun measureSkew(): Double {
        val source = original ?: return 0.0
        val rotated = Transforms.rotate(source, rotation)
        val rotatedWidth = rotated.width
        val rotatedHeight = rotated.height
        val quad = if (quadNorm.area() >= 0.999) {
            null
        } else {
            quadNorm.scaled(rotatedWidth.toDouble(), rotatedHeight.toDouble())
        }
        val page = if (quad != null) {
            runCatching { com.kscanner.core.imaging.Homography.warpPerspective(rotated, quad, 720) }
                .getOrNull() ?: rotated
        } else {
            rotated
        }
        return -Skew.estimate(page.toGray()).degrees
    }

    /** Outline in pixels of the current preview. */
    fun quadInPreview(bitmap: ImageBitmap): Quad =
        quadNorm.scaled(bitmap.width.toDouble(), bitmap.height.toDouble())

    private fun buildSettings(source: RgbaImage): ScanProcessor.Settings {
        val rotatedWidth = if (rotation % 180 == 0) source.width else source.height
        val rotatedHeight = if (rotation % 180 == 0) source.height else source.width
        val quadFull = if (quadNorm.area() >= 0.999) {
            null // full frame: skip the resampling entirely
        } else {
            quadNorm.scaled(rotatedWidth.toDouble(), rotatedHeight.toDouble())
        }
        return ScanProcessor.Settings(
            quad = quadFull,
            rotationDegrees = rotation,
            deskewDegrees = deskewDegrees,
            filter = filter,
            strength = strength,
            brightness = brightness,
            contrast = contrast,
            sharpness = sharpness,
            maxOutputEdge = settingsStore.current.maxImageEdge,
        )
    }

    /** Commits the page; [onSaved] receives the document id. */
    fun save(documentId: Long?, onSaved: (Long) -> Unit) {
        val source = original ?: return
        val path = sourcePath ?: return
        if (saving) return
        saving = true
        error = null
        viewModelScope.launch {
            runCatching {
                repository.saveCapture(documentId, path, buildSettings(source))
            }.onSuccess { id ->
                saving = false
                onSaved(id)
            }.onFailure { failure ->
                saving = false
                error = failure.message ?: "The page could not be saved."
            }
        }
    }

    fun clearError() {
        error = null
    }
}