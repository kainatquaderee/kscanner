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
import com.kscanner.core.imaging.Corners
import com.kscanner.core.imaging.DetectMethod
import com.kscanner.core.imaging.DocDetector
import com.kscanner.core.imaging.Filters
import com.kscanner.core.imaging.PageLook
import com.kscanner.core.imaging.PageTurn
import com.kscanner.core.imaging.Quad
import com.kscanner.core.imaging.RgbaImage
import com.kscanner.core.imaging.ScanFilter
import com.kscanner.core.imaging.ScanProcessor
import com.kscanner.core.imaging.Skew
import com.kscanner.core.imaging.Transforms
import com.kscanner.core.imaging.inFrame
import com.kscanner.data.ApplyLook
import com.kscanner.data.Page
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

    private companion object {
        /**
         * Longest edge of the picture the crop stage works on. Deliberately larger than the
         * detector's own working copy: the outline is placed by eye, so magnifying the page has to
         * reveal pixels rather than the blur of an enlargement.
         */
        const val CROP_PREVIEW_EDGE = 2048

        /**
         * Longest edge of the filter stage's preview. The look is judged at a glance, so this one
         * trades a little detail for a redraw the adjustment sliders can keep up with.
         */
        const val FILTER_PREVIEW_EDGE = 1920
    }

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

    /**
     * The detector's orientation decision and the user's own turns, kept apart so an automatic
     * pass can revise its half without undoing theirs; [rotation] is the sum of the two.
     */
    private var turn by mutableStateOf(PageTurn())

    /** Clockwise rotation of the whole capture, in degrees. */
    val rotation: Int get() = turn.degrees

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
    private var sourcePath: String? = null
    private var previewJob: Job? = null

    /**
     * The stored page this editor opened, or null when it is editing a fresh capture.
     *
     * Everything that differs between "save a new page" and "update the page that is already
     * there" hangs off this: a page that exists keeps its row and its position, and the document
     * around it is what "apply to all" copies onto.
     */
    private var loadedPage: Page? = null
    private var loadedPages: List<Page> = emptyList()

    /** Id of the page being edited, or -1 when this editor is preparing a new capture. */
    var editingPageId by mutableStateOf(-1L)
        private set

    /** How many pages the page being edited shares a document with, itself included. */
    var documentPageCount by mutableStateOf(0)
        private set

    /** True when the look can be copied: an existing page in a document of two or more. */
    val canApplyToAll: Boolean get() = ApplyLook.isAvailable(documentPageCount, editingPageId)

    /** How many pages "apply to all" would change: every page but the one open here. */
    val otherPageCount: Int get() = ApplyLook.targets(loadedPages, editingPageId).size

    var applying by mutableStateOf(false)
        private set

    /** "Applying page 3 of 11...", or null when no run is in flight. */
    var applyProgress by mutableStateOf<String?>(null)
        private set

    /** What the last run achieved, or null when there is nothing to report. */
    var applySummary by mutableStateOf<String?>(null)
        private set

    /**
     * Set from the main thread when the user presses Cancel on a run.
     *
     * Volatile because the run reads it between pages on a background dispatcher; a plain field
     * could leave the loop reading a stale `false` and finish a document the user stopped.
     */
    @Volatile
    private var applyCancelled = false

    /**
     * The page without its look, kept between filter changes; see [geometryFor].
     *
     * Volatile because it is built on a background dispatcher and read from whichever thread
     * happens to ask next. Without it a reader could see the holder but not the picture inside
     * it — the cache would be worse than useless.
     */
    @Volatile
    private var geometry: CachedGeometry? = null

    val hasImage: Boolean get() = original != null

    fun load(path: String, pageId: Long = -1L) {
        if (sourcePath == path && original != null && editingPageId == pageId) return
        sourcePath = path
        editingPageId = pageId
        loading = true
        analysing = true
        error = null
        viewModelScope.launch {
            // Decoded at the same edge the repository renders at. A page's stored outline is in
            // pixels of that frame, so opening it in any other frame would misplace the crop — and
            // because saving writes the outline back out, each edit would enlarge it again.
            val decoded = withContext(Dispatchers.Default) {
                BitmapBridge.decodeFile(File(path), settingsStore.current.maxImageEdge)
            }
            if (decoded == null) {
                error = "That image could not be opened."
                loading = false
                analysing = false
                return@launch
            }
            original = decoded
            val page = if (pageId >= 0) {
                withContext(Dispatchers.IO) { repository.getPage(pageId) }
            } else {
                null
            }
            if (page != null) {
                restorePage(page, decoded)
            } else {
                loadedPage = null
                loadedPages = emptyList()
                documentPageCount = 0
                stage = Stage.CROP
                turn = PageTurn()
                filter = settingsStore.defaultFilter
                strength = 1f
                brightness = 0f
                contrast = 0f
                sharpness = 0f
                deskewDegrees = 0.0
                runAutoPass(applyStyle = true)
            }
            loading = false
            analysing = false
        }
    }

    /**
     * Opens a page that already exists as it is: its stored outline, its stored rotation, its
     * stored skew and its stored look, with no automatic pass in between.
     *
     * Running detection here would be wrong twice over. It would re-decide an outline the user may
     * have placed by hand, and it would pick a preset from the original's statistics — replacing
     * the look that is the reason the page looks the way it does. Nothing is re-derived, so opening
     * a page and saving it again leaves it alone.
     *
     * A page whose original *is* its processed file has its geometry baked into its pixels, so it
     * opens with no rotation, no outline and no skew: those would otherwise be applied a second
     * time on top of pixels that already carry them.
     */
    private suspend fun restorePage(page: Page, source: RgbaImage) {
        loadedPage = page
        loadedPages = withContext(Dispatchers.IO) { repository.pages(page.documentId) }
        documentPageCount = loadedPages.size.coerceAtLeast(1)

        val baked = page.originalPath == page.processedPath
        turn = if (baked) PageTurn() else PageTurn(planQuarterTurns = 0, userQuarterTurns = (page.rotation / 90) % 4)
        quadNorm = if (baked) {
            Quad.rect(1.0, 1.0)
        } else {
            val rotatedWidth = if (rotation % 180 == 0) source.width else source.height
            val rotatedHeight = if (rotation % 180 == 0) source.height else source.width
            Corners.parse(page.corners)?.scaled(1.0 / rotatedWidth, 1.0 / rotatedHeight)
                ?: Quad.rect(1.0, 1.0)
        }
        deskewDegrees = if (baked) 0.0 else page.deskewDegrees

        filter = page.look.filter
        strength = page.look.strength
        brightness = page.look.brightness
        contrast = page.look.contrast
        sharpness = page.look.sharpness

        // Nothing was detected on this page now, so the crop stage must not claim it was.
        autoDetected = false
        detectionMethod = null
        detectionConfidence = 0.0
        advice = CaptureAdvice.GOOD

        previewBitmap = withContext(Dispatchers.Default) {
            val rotated = Transforms.rotate(source, rotation)
            BitmapBridge.toBitmap(Transforms.fitLongestEdge(rotated, CROP_PREVIEW_EDGE)).asImageBitmap()
        }
        // The look is what the page is being opened to change, so the filter stage is where it
        // lands; the crop stage is still one tap away and opens on the stored outline.
        openFilterStage()
    }

    // ------------------------------------------------------------ automatic pass

    /**
     * Runs the whole automatic preparation on the untouched capture: make it upright, find
     * the outline, measure the residual skew and the frame quality. When [applyStyle] is
     * set the enhancement preset is also chosen from the frame's own measurements.
     *
     * The pass re-decides nothing but its own half of [turn]: whatever the user turned by hand
     * before pressing Detect survives, and the outline is moved with their turn.
     */
    private suspend fun runAutoPass(applyStyle: Boolean) {
        val source = original ?: return
        // Read before the background hop: the pass is allowed to revise the plan's half of this,
        // and nothing else about the turn.
        val inherited = turn
        val prepared = withContext(Dispatchers.Default) {
            val plan = AutoCrop.plan(
                source,
                workingEdge = 1080,
                fixOrientation = settingsStore.current.autoRotate,
            )
            // AutoCrop states its decision in quarter turns, so it drops straight into the plan's
            // half; the preview is then rendered in the frame that decision and the user's turn
            // together produce, which is the frame the outline below is expressed in.
            val planned = inherited.withPlan(plan.rotationDegrees / 90)
            val rotated = Transforms.rotate(source, planned.degrees)
            if (applyStyle && settingsStore.current.autoFilter) {
                // The preset is chosen from the frame's statistics alone, so the cheap copy the
                // planner already works at is enough and the displayed one is not paid for twice.
                filter = Filters.auto(Transforms.fitLongestEdge(rotated, 1080))
            }
            Prepared(planned, plan, Transforms.fitLongestEdge(rotated, CROP_PREVIEW_EDGE))
        }

        turn = prepared.turn
        deskewDegrees = if (settingsStore.current.autoDeskew) prepared.plan.deskewDegrees else 0.0
        detectionMethod = prepared.plan.method
        detectionConfidence = prepared.plan.confidence
        advice = prepared.plan.quality.advice

        // Take the outline from the plan as 0..1 fractions of the frame. It is *not* re-derived
        // here: the plan's other outline is in full-resolution pixels, and dividing that by the
        // on-screen preview size inflated it by the downscale factor and dragged it off the
        // picture. AutoCrop has already rejected any outline that leaves the frame.
        //
        // The plan owns only the frame its rotation produced; the user's turn moved the picture
        // on from there, so the outline is carried through it as well.
        val found = prepared.plan.quadNormalised
        quadNorm = found?.let { prepared.turn.applyTo(it) } ?: Quad.rect(1.0, 1.0).insetBy(0.025)
        autoDetected = found != null

        previewBitmap = withContext(Dispatchers.Default) {
            BitmapBridge.toBitmap(prepared.preview).asImageBitmap()
        }
    }

    private class Prepared(
        /** The plan's decision with the user's turn on top; these degrees are the frame below. */
        val turn: PageTurn,
        val plan: AutoCrop.Plan,
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
            val turns = if (clockwise) 1 else -1

            // Rotate the outline with the pixels so it still describes the same physical edge.
            // The turn is recorded as the user's own, which is what keeps Detect from reversing it.
            val movedQuad = currentQuad.rotatedQuarterTurns(turns)

            turn = turn.turnedBy(turns)
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
            if (measured != deskewDegrees) {
                deskewDegrees = measured
                invalidateGeometry()
            }
            if (stage == Stage.FILTER) recomputeResult()
            loading = false
        }
    }

    fun clearDeskew() {
        if (deskewDegrees == 0.0) return
        deskewDegrees = 0.0
        invalidateGeometry()
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
        invalidateGeometry()
        recomputeResult(immediate = true)
    }

    fun backToCropStage() {
        stage = Stage.CROP
        previewJob?.cancel()
        invalidateGeometry()
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
        val key = GeometryKey(
            path = sourcePath.orEmpty(),
            rotation = rotation,
            quad = quadNorm,
            deskewDegrees = deskewDegrees,
        )
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(110)
            val rendered = withContext(Dispatchers.Default) {
                val geometry = geometryFor(source, key, settings)
                ScanProcessor.enhance(geometry, settings)
            }
            resultBitmap = withContext(Dispatchers.Default) {
                BitmapBridge.toBitmap(rendered).asImageBitmap()
            }
        }
    }

    /**
     * The page without its look, rebuilt only when the outline, the rotation or the skew moved.
     *
     * The whole point of the filter stage is to compare looks, and it re-renders on every slider
     * frame. Rotation, warping and deskewing do not depend on any of those sliders, so they are
     * kept here and only the enhancement is re-run on top.
     *
     * The key is stored *with* the picture in one immutable holder rather than next to it, so a
     * reader can never pair an old outline with a new picture. A key that is stale simply misses and
     * costs one rebuild; it can never produce the wrong page.
     */
    private fun geometryFor(
        source: RgbaImage,
        key: GeometryKey,
        settings: ScanProcessor.Settings,
    ): ScanProcessor.PageGeometry {
        val cached = geometry
        if (cached != null && cached.key == key) return cached.geometry
        return ScanProcessor.previewGeometry(source, settings, FILTER_PREVIEW_EDGE).also {
            geometry = CachedGeometry(key, it)
        }
    }

    /**
     * Forgets the cached page.
     *
     * Only two things can move the geometry while the filter stage is up — the deskew buttons — and
     * both call this. Leaving the stage calls it as well, so the buffer is never held while the crop
     * stage is what the user is looking at. [GeometryKey] would catch a stale entry on its own, so
     * this is about releasing memory rather than about correctness.
     */
    private fun invalidateGeometry() {
        geometry = null
    }

    /** Everything in the settings that [ScanProcessor.previewGeometry] reads, by value. */
    private data class GeometryKey(
        val path: String,
        val rotation: Int,
        val quad: Quad,
        val deskewDegrees: Double,
    )

    /** A page geometry together with the geometry it was built from. */
    private data class CachedGeometry(
        val key: GeometryKey,
        val geometry: ScanProcessor.PageGeometry,
    )

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
            val preview = Transforms.fitLongestEdge(rotated, CROP_PREVIEW_EDGE)
            val detected = if (detect) {
                DocDetector.detect(preview)?.quad
            } else {
                null
            }
            val skew = if (remeasureSkew) measureSkew() else null
            Triple(preview, detected, skew)
        }
        val (preview, found, skew) = rendered

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

    private fun currentLook(): PageLook = PageLook(
        filter = filter,
        strength = strength,
        brightness = brightness,
        contrast = contrast,
        sharpness = sharpness,
    )

    private fun buildSettings(source: RgbaImage): ScanProcessor.Settings {
        val rotatedWidth = if (rotation % 180 == 0) source.width else source.height
        val rotatedHeight = if (rotation % 180 == 0) source.height else source.width
        // The look and the geometry meet in PageLook.toSettings, which is also what every
        // re-render of a saved page uses, so the page saved here and the page rendered later
        // cannot disagree about what it looks like.
        return currentLook().toSettings(
            quad = quadNorm.inFrame(rotatedWidth, rotatedHeight),
            rotationDegrees = rotation,
            deskewDegrees = deskewDegrees,
            maxOutputEdge = settingsStore.current.maxImageEdge,
        )
    }

    /**
     * Commits the page; [onSaved] receives the document id.
     *
     * A page opened from an existing document is *updated*: same row, same position, new bytes.
     * Saving it as a new page would leave the document holding both the old page and a copy of it,
     * and the copy would be at the end of the run rather than where the user was.
     */
    fun save(documentId: Long?, onSaved: (Long) -> Unit) {
        val source = original ?: return
        val path = sourcePath ?: return
        if (saving) return
        saving = true
        error = null
        val pageId = editingPageId
        val fallbackDocumentId = documentId ?: loadedPage?.documentId
        viewModelScope.launch {
            runCatching {
                if (pageId >= 0) {
                    val updated = repository.updatePage(
                        pageId = pageId,
                        outlineNorm = quadNorm,
                        rotationDegrees = rotation,
                        deskewDegrees = deskewDegrees,
                        look = currentLook(),
                    )
                    if (!updated) throw IllegalStateException("That page could not be saved.")
                    fallbackDocumentId ?: throw IllegalStateException("That page could not be saved.")
                } else {
                    repository.saveCapture(documentId, path, buildSettings(source))
                }
            }.onSuccess { id ->
                saving = false
                onSaved(id)
            }.onFailure { failure ->
                saving = false
                error = failure.message ?: "The page could not be saved."
            }
        }
    }

    // ------------------------------------------------------------ apply to all

    /**
     * Copies the look on screen onto every other page of the document.
     *
     * Only the look: each page is re-rendered from its own original through its own outline,
     * rotation and skew. The page open here is left alone — it is what the others are being made
     * to match. Cancelling stops the run but does not undo it; [applySummary] reports how far it
     * got rather than pretending the document is as it was.
     */
    fun applyToAll() {
        if (applying || !canApplyToAll) return
        val page = loadedPage ?: return
        val targets = ApplyLook.targets(loadedPages, editingPageId)
        if (targets.isEmpty()) return

        val look = currentLook()
        applying = true
        applyCancelled = false
        applyProgress = null
        applySummary = null
        viewModelScope.launch {
            val result = runCatching {
                repository.applyLookToPages(
                    documentId = page.documentId,
                    pageIds = targets.map { it.id },
                    look = look,
                    onProgress = { number, total -> applyProgress = ApplyLook.progress(number, total) },
                    cancelled = { applyCancelled },
                )
            }
            applying = false
            applyProgress = null
            result.onSuccess { outcome ->
                applySummary = ApplyLook.summary(
                    applied = outcome.applied,
                    failed = outcome.failed,
                    cancelled = outcome.cancelled,
                    total = targets.size,
                )
            }.onFailure { failure ->
                error = failure.message ?: "The look could not be applied."
            }
        }
    }

    /** Asks the running copy to stop after the page it is on. */
    fun cancelApply() {
        applyCancelled = true
    }

    fun clearApplySummary() {
        applySummary = null
    }

    fun clearError() {
        error = null
    }
}