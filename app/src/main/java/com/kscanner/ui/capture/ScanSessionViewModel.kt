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
 * Drives the runs that keep the camera open.
 *
 * A shot arrives, it is prepared without asking anything, and pages land in a document. The
 * mode decides what one shot becomes; the Batch toggle decides whether the run goes on after
 * the shot or stops there. The two are orthogonal, so "a book spread, and keep going" is
 * expressible.
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

    /**
     * Whether the camera keeps shooting into one growing document.
     *
     * Session state like [mode], not a saved preference: the Settings screen decides what the
     * camera opens with, and a change made here lasts as long as the run does.
     */
    var batch by mutableStateOf(settings.current.batch)
        private set

    /**
     * True when a shot belongs in the editor rather than the document: the one-page shape with
     * Batch off, which is the "place the corners yourself" case.
     */
    val capturesToEditor: Boolean get() = mode == ScanMode.SINGLE && !batch

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

    /**
     * The page the next shot will replace, or null when no re-take is armed.
     *
     * Held as a row id, not an index: the point of a re-take is that the page keeps its slot,
     * so a position that moved is still the right page.
     */
    var retakeTarget by mutableStateOf<Long?>(null)
        private set

    /** 1-based position of [retakeTarget], so the prompt can name the page it will replace. */
    var retakePosition by mutableStateOf<Int?>(null)
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
        // A re-take is armed against the shape of the run, so changing the shape has to drop it.
        cancelRetake()
        status = if (next == ScanMode.ID_CARD) "Capture the front of the card" else null
    }

    /** Turns the Batch toggle over: keep shooting into this document, or stop after one unit. */
    fun updateBatch(next: Boolean) {
        if (next == batch) return
        batch = next
        cancelRetake()
    }

    /** Preferred mode when the camera opens, without persisting a change mid-session. */
    fun prepare(initial: ScanMode, initialBatch: Boolean) {
        mode = initial
        batch = initialBatch
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
    fun prepareIfNeeded(initial: ScanMode, initialBatch: Boolean, documentId: Long?) {
        if (prepared) return
        prepared = true
        prepare(initial, initialBatch)
        beginInto(documentId)
    }

    private var prepared = false

    fun beginInto(documentId: Long?) {
        this.documentId = documentId
        pageCount = 0
        recent = emptyList()
        review = null
        retakeTarget = null
        retakePosition = null
        heldSide = null
        error = null
    }

    fun consume(path: String, onPagesAdded: (Int) -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        // Claimed now rather than read when the shot lands: a re-take arms exactly one shot,
        // and the capture after it must append like any other.
        val target = retakeTarget
        retakeTarget = null
        retakePosition = null
        viewModelScope.launch {
            runCatching { if (target == null) prepareAndStore(path) else replacePage(path, target) }
                .onSuccess { added -> if (added > 0) onPagesAdded(added) }
                .onFailure { failure ->
                    error = failure.message ?: "That shot could not be processed."
                }
            // The capture file is scratch: its pixels now live inside the document.
            withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
            busy = false
        }
    }

    /**
     * Puts a fresh capture where the page it replaces already sits.
     *
     * @return zero, because the document's page count has not changed: a caller must not treat
     *   this as a page being added
     */
    private suspend fun replacePage(path: String, pageId: Long): Int {
        val replacement = withContext(Dispatchers.Default) {
            measure(path, mode).singlePage(path)
        }
        val newId = withContext(Dispatchers.IO) { repository.retakePage(pageId, replacement) }
            ?: throw IllegalStateException("That page is no longer in this document.")

        // The strip holds row ids and this page now has a new one, so the tile is swapped in
        // place: the strip keeps its order, and the tile still opens the page it shows.
        val thumbnail = withContext(Dispatchers.Default) {
            BitmapBridge.toBitmap(Transforms.fitLongestEdge(replacement.image, 220)).asImageBitmap()
        }
        recent = recent.map { tile ->
            if (tile.pageId == pageId) {
                tile.copy(pageId = newId, bitmap = thumbnail, flipDecided = replacement.flipDecided)
            } else {
                tile
            }
        }
        status = "Page replaced"
        return 0
    }

    private suspend fun prepareAndStore(path: String): Int {
        val taken = mode
        val measured = measure(path, taken)
        return when (taken) {
            ScanMode.SINGLE -> {
                val page = withContext(Dispatchers.Default) { measured.singlePage(path) }
                commit(listOf(page))
            }

            ScanMode.BOOK -> {
                val pages = withContext(Dispatchers.Default) { measured.spreadPages() }
                commit(pages)
            }

            ScanMode.ID_CARD -> {
                val rendered = withContext(Dispatchers.Default) { measured.wholeShot() }
                val first = heldSide
                if (first == null) {
                    heldSide = rendered
                    status = "Front captured - now the back"
                    0
                } else {
                    heldSide = null
                    status = "Both sides captured"
                    val sheet = withContext(Dispatchers.Default) { Composite.stacked(first, rendered) }
                    commit(listOf(sheet.toPage(measured.filter.id, measured.flipDecided)))
                }
            }
        }
    }

    /**
     * Decodes a capture and takes the decisions every shape shares: which filter to use, how
     * the shot should be turned, and the scan settings those imply.
     *
     * A re-take is measured here too, so a replacement is produced by the same rules as the
     * page it replaces; a re-shot page cropped differently from its neighbours would be a
     * defect of its own.
     */
    private suspend fun measure(path: String, taken: ScanMode): Measured {
        val current = settings.current
        val source = withContext(Dispatchers.Default) {
            BitmapBridge.decodeFile(File(path), current.maxImageEdge)
        } ?: throw IllegalStateException("the capture could not be decoded")

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
                aspectHint = taken.aspectHint,
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

        return Measured(
            source = source,
            filter = filter,
            plan = plan,
            base = base,
        )
    }

    /**
     * A decoded shot and the decisions taken on it.
     *
     * Holding these together is what lets a first shot and a re-shot be rendered by one piece
     * of code: the two paths differ in where the result goes, not in how it is made.
     */
    private class Measured(
        private val source: RgbaImage,
        val filter: ScanFilter,
        private val plan: AutoCrop.Plan,
        private val base: ScanProcessor.Settings,
    ) {
        /** Whether the automatic pass could decide the page's up direction. */
        val flipDecided: Boolean? get() = plan.flipDecided

        /** The whole shot, cropped to the outline the detector proposed when there is one. */
        fun wholeShot(): RgbaImage = ScanProcessor.process(source, base.copy(quad = plan.quad))

        /** The whole shot in the form a page is stored as. */
        fun singlePage(originalPath: String?): ScanRepository.RenderedPage {
            val image = wholeShot()
            return ScanRepository.RenderedPage(
                image = image,
                originalPath = originalPath,
                quad = plan.quad,
                rotation = plan.rotationDegrees,
                filterId = filter.id,
                flipDecided = plan.flipDecided,
            )
        }

        /**
         * Renders a Book-mode shot: two pages when the shot really is a spread, one page when it
         * is not.
         *
         * The decision comes from the gutter found on the flattened shot, because cutting every
         * shot in two turned a photograph of one page into two half-pages that nothing in the app
         * can rejoin. A shot decided to be one page is rendered exactly as a single shot is, and
         * keeps its whole width; a spread keeps the existing per-half homography, cut where the
         * book actually folds rather than down the middle.
         */
        fun spreadPages(): List<ScanRepository.RenderedPage> {
            val rotated = Transforms.rotate(source, base.rotationDegrees)
            val spread = plan.quad ?: Quad.rect(
                rotated.width.toDouble(),
                rotated.height.toDouble(),
            ).insetBy(0.05)

            val flattened = runCatching {
                Homography.warpPerspective(rotated, spread.clampTo(rotated.width, rotated.height), 900)
            }.getOrNull()
            // A shot the pipeline could not flatten has no evidence either way, and the same
            // default applies: a whole page is what the user can see and re-shoot, two halves
            // are not.
            val verdict = flattened?.let { Spread.decide(it.toGray()) }
            if (verdict == null || !verdict.isSpread) return listOf(singlePage(null))

            val split = Spread.split(spread, verdict.seam)

            return listOf(split.left, split.right).map { half ->
                val rendered = ScanProcessor.process(source, base.copy(quad = half))
                ScanRepository.RenderedPage(
                    image = rendered,
                    originalPath = null,
                    quad = half,
                    rotation = base.rotationDegrees,
                    filterId = base.filter.id,
                    flipDecided = plan.flipDecided,
                )
            }
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

    /**
     * Whether the review should offer a re-take of the page on screen.
     *
     * One shot has to mean one page, which leaves out a spread (one shot, two pages) and a card
     * (two shots, one page): "replace this page with one shot" has no single answer there. Batch
     * has to be on for the same reason — a run that stops after one unit is not a run a
     * replacement can be shot into.
     */
    val canRetake: Boolean
        get() = mode.isOnePagePerShot && batch && documentId != null

    /**
     * Arms a re-take of the page the review is showing, and closes the review.
     *
     * The next shot replaces that page where it already sits. Nothing is written until that shot
     * arrives, so leaving the camera without shooting leaves the page as it was — the re-take is
     * a prompt, not an edit.
     */
    fun retakeReviewPage() {
        val state = review ?: return
        val page = state.current ?: return
        if (!canRetake) return
        retakeTarget = page.id
        retakePosition = page.index + 1
        status = "Re-take: shoot the replacement for page ${page.index + 1}"
        review = null
    }

    /** Drops an armed re-take, leaving the page it pointed at untouched. */
    fun cancelRetake() {
        if (retakeTarget == null) return
        retakeTarget = null
        retakePosition = null
        status = null
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
