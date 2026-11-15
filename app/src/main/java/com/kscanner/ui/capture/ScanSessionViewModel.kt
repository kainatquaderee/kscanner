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
import com.kscanner.data.Page
import com.kscanner.data.ScanMode
import com.kscanner.data.ScanRepository
import com.kscanner.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One tile in the strip under the shutter. The id is what makes a tap able to open it, and
 * [number] is the page's 1-based position in the run, carried rather than derived from the
 * tile's index: a book spread commits two pages at once, so counting back from the front of
 * a newest-first strip would number a spread's halves the wrong way round.
 */
data class StripTile(
    val pageId: Long,
    val documentId: Long,
    val bitmap: ImageBitmap,
    val flipDecided: Boolean?,
    val number: Int,
)

/** The full-screen review of what a run has committed, or null when it is closed. */
data class ReviewState(val pages: List<Page>, val index: Int) {
    val current: Page? get() = pages.getOrNull(index)
    val position: String get() = "${index + 1} of ${pages.size}"
}

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

    /** Most recent pages, newest commit first, for the strip under the shutter. */
    var recent by mutableStateOf<List<StripTile>>(emptyList())
        private set

    /** The review, or null when it is closed. */
    var review by mutableStateOf<ReviewState?>(null)
        private set

    /** Guards the review's rotate/flip against a second tap landing mid-render. */
    private var reviewBusy = false

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

    /**
     * Sets the session up the first time the camera is shown and does nothing afterwards.
     *
     * [beginInto] resets the document, the page count and the strip, so running it again
     * after a trip away from this screen would wipe the run in progress. Initialising once
     * is what lets the review be an overlay the user can leave and come back from — the
     * session survives the whole batch either way.
     */
    fun prepareIfNeeded(initial: ScanMode, documentId: Long?) {
        if (prepared) return
        prepared = true
        prepare(initial)
        beginInto(documentId)
    }

    private var prepared = false

    fun beginInto(documentId: Long?) {
        this.documentId = documentId
        pageCount = 0
        recent = emptyList()
        review = null
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
                            flipDecided = plan.flipDecided,
                        ),
                    ),
                )
            }

            ScanMode.BOOK -> {
                val halves = withContext(Dispatchers.Default) {
                    renderSpread(source, base, plan.quad, plan.flipDecided)
                }
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
                    commit(listOf(sheet.toPage(filter.id, plan.flipDecided)))
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
        flipDecided: Boolean?,
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
                flipDecided = flipDecided,
            )
        }
    }

    private fun RgbaImage.toPage(filterId: Int, flipDecided: Boolean?) = ScanRepository.RenderedPage(
        image = this,
        originalPath = null,
        quad = null,
        rotation = 0,
        filterId = filterId,
        flipDecided = flipDecided,
    )

    private suspend fun commit(pages: List<ScanRepository.RenderedPage>): Int {
        val result = repository.appendPages(
            documentId = documentId,
            pages = pages,
            documentName = if (mode == ScanMode.BOOK) "Book scan" else "Scan",
        )
        val firstNumber = pageCount + 1
        documentId = result.documentId
        pageCount += pages.size

        // The strip is newest commit first, and within a commit stays in reading order: the
        // halves of one spread are [left, right], and reversing them would swap the two. The
        // page number travels with the tile because a commit can be two pages wide, so the
        // tile's index in the strip is not the page's number.
        val tiles = withContext(Dispatchers.Default) {
            pages.mapIndexedNotNull { index, page ->
                val id = result.pageIds.getOrNull(index) ?: return@mapIndexedNotNull null
                val small = Transforms.fitLongestEdge(page.image, 220)
                StripTile(
                    pageId = id,
                    documentId = result.documentId,
                    bitmap = BitmapBridge.toBitmap(small).asImageBitmap(),
                    flipDecided = page.flipDecided,
                    number = firstNumber + index,
                )
            }
        }
        recent = (tiles + recent).take(STRIP_CAP)
        return pages.size
    }

    // ---------------------------------------------------------------- review

    /** Opens the review on [pageId], or does nothing when that page is not in this run. */
    fun openReview(pageId: Long) {
        val id = documentId ?: return
        viewModelScope.launch {
            // The rows the document screen reads, so the review shows what was committed —
            // not the strip's handful of in-memory thumbnails, which cannot reach the far
            // end of a long run.
            val rows = withContext(Dispatchers.IO) { repository.pages(id) }
            val index = rows.indexOfFirst { it.id == pageId }
            if (index >= 0) review = ReviewState(rows, index)
        }
    }

    fun closeReview() {
        review = null
    }

    fun stepReview(delta: Int) {
        val state = review ?: return
        if (state.pages.isEmpty()) return
        review = state.copy(index = (state.index + delta).coerceIn(0, state.pages.lastIndex))
    }

    fun rotateReviewPage(clockwise: Boolean) = actOnReviewPage("That page could not be rotated.") {
        repository.rotateBy(it, if (clockwise) 90 else 270)
    }

    /** One-tap 180 for a page the automatic pass said it could not orient. */
    fun flipReviewPage() = actOnReviewPage("That page could not be turned over.") {
        repository.flipPage(it)
    }

    private fun actOnReviewPage(failure: String, action: suspend (Long) -> Boolean) {
        val state = review ?: return
        val page = state.current ?: return
        if (reviewBusy) return
        reviewBusy = true
        error = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { action(page.id) }
            reviewBusy = false
            if (!ok) {
                // A rotate that fails in silence is the defect this screen exists to fix.
                error = failure
            } else {
                replaceReviewPage(page.id)
            }
        }
    }

    /** Re-reads one page's row, so the review repaints from the file just rewritten. */
    private suspend fun replaceReviewPage(pageId: Long) {
        val state = review ?: return
        val fresh = withContext(Dispatchers.IO) { repository.getPage(pageId) } ?: return
        review = state.copy(pages = state.pages.map { if (it.id == pageId) fresh else it })
    }

    fun clearError() {
        error = null
    }

    private companion object {
        /** Strip tiles kept under the shutter. The review is not limited by this. */
        const val STRIP_CAP = 12
    }
}
