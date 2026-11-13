package com.kscanner.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kscanner.core.imaging.AutoCrop
import com.kscanner.core.imaging.BitmapBridge
import com.kscanner.core.imaging.Composite
import com.kscanner.core.imaging.Filters
import com.kscanner.core.imaging.Homography
import com.kscanner.core.imaging.Quad
import com.kscanner.core.imaging.RgbaImage
import com.kscanner.core.imaging.ScanFilter
import com.kscanner.core.imaging.ScanProcessor
import com.kscanner.core.imaging.Spread
import com.kscanner.core.imaging.Transforms
import com.kscanner.data.ScanMode
import com.kscanner.data.ScanRepository
import com.kscanner.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the continuous capture modes.
 *
 * Batch, book and ID-card scanning all share one shape: a shot arrives, it is prepared
 * without asking the user anything, and pages land in a document that grows until the user
 * says it is done. The mode only changes what a single shot becomes.
 *
 * Everything pixel-level happens in the framework-free imaging engine, so this class is
 * thin: decode, decide, delegate, append.
 */
class ScanSessionViewModel(
    private val repository: ScanRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    var mode by mutableStateOf(settings.current.scanMode)
        private set

    var busy by mutableStateOf(false)
        private set

    /** Human-readable state, e.g. "Front captured - now the back". */
    var status by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Document under construction, or null until the first page is written. */
    var documentId by mutableStateOf<Long?>(null)
        private set

    var pageCount by mutableStateOf(0)
        private set

    /** Most recent pages, newest first, for the strip under the shutter. */
    var recent by mutableStateOf<List<ImageBitmap>>(emptyList())
        private set

    /** First side of an ID card waiting for its pair. */
    private var heldSide: RgbaImage? = null

    val needsSecondSide: Boolean get() = mode == ScanMode.ID_CARD && heldSide != null

    fun updateMode(next: ScanMode) {
        if (next == mode) return
        mode = next
        heldSide = null
        status = if (next == ScanMode.ID_CARD) "Capture the front of the card" else null
    }

    /** Preferred mode when the camera opens, without persisting a change mid-session. */
    fun prepare(initial: ScanMode) {
        mode = initial
        heldSide = null
        status = if (initial == ScanMode.ID_CARD) "Capture the front of the card" else null
    }

    fun beginInto(documentId: Long?) {
        this.documentId = documentId
        pageCount = 0
        recent = emptyList()
        heldSide = null
        error = null
    }

    fun consume(path: String, onPagesAdded: (Int) -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { prepareAndStore(path) }
                .onSuccess { added -> if (added > 0) onPagesAdded(added) }
                .onFailure { failure ->
                    error = failure.message ?: "That shot could not be processed."
                }
            // The capture file is scratch: its pixels now live inside the document.
            withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
            busy = false
        }
    }

    private suspend fun prepareAndStore(path: String): Int {
        val current = settings.current
        val source = withContext(Dispatchers.Default) {
            BitmapBridge.decodeFile(File(path), current.maxImageEdge)
        } ?: throw IllegalStateException("the capture could not be decoded")

        val mode = mode
        val filter = withContext(Dispatchers.Default) {
            if (current.autoFilter) {
                Filters.auto(Transforms.fitLongestEdge(source, 1080))
            } else {
                settings.defaultFilter
            }
        }

        val plan = withContext(Dispatchers.Default) {
            AutoCrop.plan(
                source,
                workingEdge = 1080,
                aspectHint = mode.aspectHint,
                fixOrientation = current.autoRotate,
            )
        }
        val deskew = if (current.autoDeskew) plan.deskewDegrees else 0.0

        val base = ScanProcessor.Settings(
            quad = null,
            rotationDegrees = plan.rotationDegrees,
            deskewDegrees = deskew,
            filter = filter,
            maxOutputEdge = current.maxImageEdge,
        )

        return when (mode) {
            ScanMode.SINGLE -> 0

            ScanMode.BATCH -> {
                val rendered = withContext(Dispatchers.Default) {
                    ScanProcessor.process(source, base.copy(quad = plan.quad))
                }
                commit(
                    listOf(
                        ScanRepository.RenderedPage(
                            image = rendered,
                            originalPath = path,
                            quad = plan.quad,
                            rotation = plan.rotationDegrees,
                            filterId = filter.id,
                        ),
                    ),
                )
            }

            ScanMode.BOOK -> {
                val halves = withContext(Dispatchers.Default) { renderSpread(source, base, plan.quad) }
                commit(halves)
            }

            ScanMode.ID_CARD -> {
                val rendered = withContext(Dispatchers.Default) {
                    ScanProcessor.process(source, base.copy(quad = plan.quad))
                }
                val first = heldSide
                if (first == null) {
                    heldSide = rendered
                    status = "Front captured - now the back"
                    0
                } else {
                    heldSide = null
                    status = "Both sides captured"
                    val sheet = withContext(Dispatchers.Default) { Composite.stacked(first, rendered) }
                    commit(listOf(sheet.toPage(filter.id)))
                }
            }
        }
    }

    /**
     * Cuts a spread into two pages. The gutter is found on the flattened spread, so the
     * cut follows where the book actually folds rather than the geometric middle.
     */
    private fun renderSpread(
        source: RgbaImage,
        base: ScanProcessor.Settings,
        detected: Quad?,
    ): List<ScanRepository.RenderedPage> {
        val rotated = Transforms.rotate(source, base.rotationDegrees)
        val spread = detected ?: Quad.rect(
            rotated.width.toDouble(),
            rotated.height.toDouble(),
        ).insetBy(0.05)

        val flattened = runCatching {
            Homography.warpPerspective(rotated, spread.clampTo(rotated.width, rotated.height), 900)
        }.getOrNull()
        val seam = flattened?.let { Spread.findSeam(it.toGray()) } ?: 0.5
        val split = Spread.split(spread, seam)

        return listOf(split.left, split.right).map { half ->
            val rendered = ScanProcessor.process(source, base.copy(quad = half))
            ScanRepository.RenderedPage(
                image = rendered,
                originalPath = null,
                quad = half,
                rotation = base.rotationDegrees,
                filterId = base.filter.id,
            )
        }
    }

    private fun RgbaImage.toPage(filterId: Int) = ScanRepository.RenderedPage(
        image = this,
        originalPath = null,
        quad = null,
        rotation = 0,
        filterId = filterId,
    )

    private suspend fun commit(pages: List<ScanRepository.RenderedPage>): Int {
        val id = repository.appendPages(
            documentId = documentId,
            pages = pages,
            documentName = if (mode == ScanMode.BOOK) "Book scan" else "Scan",
        )
        documentId = id
        pageCount += pages.size

        val thumbs = withContext(Dispatchers.Default) {
            pages.map { page ->
                val small = Transforms.fitLongestEdge(page.image, 220)
                BitmapBridge.toBitmap(small).asImageBitmap()
            }
        }
        recent = (thumbs.reversed() + recent).take(12)
        return pages.size
    }

    fun clearError() {
        error = null
    }
}
