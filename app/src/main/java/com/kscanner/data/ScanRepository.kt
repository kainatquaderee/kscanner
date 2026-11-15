package com.kscanner.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.kscanner.core.imaging.BitmapBridge
import com.kscanner.core.imaging.Corners
import com.kscanner.core.imaging.Quad
import com.kscanner.core.imaging.RgbaImage
import com.kscanner.core.imaging.ScanFilter
import com.kscanner.core.imaging.ScanProcessor
import com.kscanner.core.imaging.Transforms
import com.kscanner.core.ocr.OcrEngine
import com.kscanner.core.ocr.OcrText
import com.kscanner.core.pdf.PdfExporter
import com.kscanner.core.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coordinates the document store, on-disk storage, imaging pipeline, OCR and PDF export.
 * Every method that does real work runs off the main thread.
 */
class ScanRepository(
    private val context: Context,
    private val store: DocumentStore,
    private val storage: StorageManager,
    private val settings: SettingsStore,
    private val ocr: OcrEngine,
) {

    // ---------------------------------------------------------------- documents

    suspend fun createDocument(name: String? = null): Long = withContext(Dispatchers.IO) {
        store.insertDocument(name ?: defaultDocumentName(), null)
    }

    suspend fun renameDocument(id: Long, name: String) = withContext(Dispatchers.IO) {
        store.renameDocument(id, name.trim().ifBlank { defaultDocumentName() })
    }

    suspend fun moveDocument(id: Long, folder: String?) = withContext(Dispatchers.IO) {
        store.moveDocument(id, folder?.trim()?.ifBlank { null })
    }

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        store.deleteDocument(id)
        storage.deleteDocument(id)
    }

    suspend fun getDocument(id: Long): Document? = withContext(Dispatchers.IO) { store.getDocument(id) }

    suspend fun pages(documentId: Long): List<Page> = withContext(Dispatchers.IO) { store.getPages(documentId) }

    suspend fun getPage(pageId: Long): Page? = withContext(Dispatchers.IO) { store.getPage(pageId) }

    // ---------------------------------------------------------------- capture

    /**
     * A page whose pixels are already final. This is the shared currency of every capture
     * mode: batch writes one per shot, book writes two from one spread, ID card writes one
     * built from two shots.
     */
    class RenderedPage(
        val image: RgbaImage,
        /** File kept as the reprocessable original, or null when there is no single source. */
        val originalPath: String?,
        val quad: Quad?,
        val rotation: Int,
        val filterId: Int,
        /**
         * Whether the automatic pass could decide the page's 0-versus-180 flip; null when no
         * plan ran. Stored so the review can offer a one-tap flip on the pages it could not
         * decide, and stay quiet on the ones it could.
         */
        val flipDecided: Boolean? = null,
    )

    /** Where a batch of rendered pages landed: the document, and each page's row id in order. */
    class AppendResult(val documentId: Long, val pageIds: List<Long>)

    /**
     * What an import actually achieved. [documentId] is the document the pages went into, or
     * null when not one of [uris] could be read — the two cases the caller has to tell apart.
     */
    class ImportResult(val documentId: Long?, val imported: Int, val failed: Int)

    /**
     * Writes already-rendered pages to a document, creating the document first when
     * [documentId] is null.
     *
     * @return the document the pages were added to, with the row id of every page in the
     *   order they were given — the ids are what make a strip tile able to open its page
     */
    suspend fun appendPages(
        documentId: Long?,
        pages: List<RenderedPage>,
        documentName: String? = null,
    ): AppendResult = withContext(Dispatchers.Default) {
        require(pages.isNotEmpty()) { "nothing to write" }

        val id = documentId ?: withContext(Dispatchers.IO) {
            store.insertDocument(documentName ?: defaultDocumentName(), null)
        }

        val pageIds = ArrayList<Long>(pages.size)
        // Not the row count: after a delete or a retake, counting puts the next page in the
        // middle of the document. See DocumentStore.nextPageIndex.
        var index = withContext(Dispatchers.IO) { store.nextPageIndex(id) }
        for (page in pages) {
            val pageFile = withContext(Dispatchers.IO) { storage.newPageFile(id, "page") }
            if (!BitmapBridge.saveJpeg(page.image, pageFile, settings.current.jpegQuality)) {
                throw IllegalStateException("could not write the scanned page")
            }

            val originalFile = withContext(Dispatchers.IO) {
                val source = page.originalPath
                if (settings.current.keepOriginals && source != null) {
                    val dest = storage.newPageFile(id, "original")
                    if (BitmapBridge.copyToFile(File(source).inputStream(), dest)) dest else null
                } else {
                    null
                }
            }

            pageIds.add(
                withContext(Dispatchers.IO) {
                    store.insertPage(
                        documentId = id,
                        index = index,
                        originalPath = (originalFile ?: pageFile).absolutePath,
                        processedPath = pageFile.absolutePath,
                        filterId = page.filterId,
                        rotation = page.rotation,
                        corners = page.quad?.let { Corners.serialize(it) },
                        width = page.image.width,
                        height = page.image.height,
                        flipDecided = page.flipDecided,
                    )
                },
            )

            if (settings.current.autoOcr) {
                runCatching { runOcrForPage(id, index) }
            }
            index++
        }

        withContext(Dispatchers.IO) { refreshThumbnail(id) }
        AppendResult(id, pageIds)
    }

    /**
     * Processes a capture and appends it to a document, creating the document first when
     * [documentId] is null.
     *
     * @return the document id the page was added to
     */
    suspend fun saveCapture(
        documentId: Long?,
        capturePath: String,
        scan: ScanProcessor.Settings,
        documentName: String? = null,
    ): Long = withContext(Dispatchers.Default) {
        val sourceFile = File(capturePath)
        val source = BitmapBridge.decodeFile(sourceFile, settings.current.maxImageEdge)
            ?: throw IllegalStateException("capture could not be decoded")

        val rendered = ScanProcessor.process(source, scan)
        appendPages(
            documentId = documentId,
            pages = listOf(
                RenderedPage(
                    image = rendered,
                    originalPath = capturePath,
                    quad = scan.quad,
                    rotation = scan.rotationDegrees,
                    filterId = scan.filter.id,
                ),
            ),
            documentName = documentName,
        ).documentId
    }

    /**
     * Imports gallery images, each becoming its own page with automatic edge detection.
     *
     * A picker hands over up to 40 images and any of them may be unreadable, so the counts
     * travel back with the id: collapsing that to `null` was how a lost page went unmentioned.
     *
     * @return the document the pages landed in (null when none did), plus how many images
     *   were imported and how many could not be read
     */
    suspend fun importImages(uris: List<Uri>, documentId: Long? = null): ImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ImportResult(documentId, 0, 0)

        var target = documentId
        var imported = 0
        var failures = 0
        for (uri in uris) {
            val bitmap = decodeBounded(uri)
            if (bitmap == null) {
                failures++
                continue
            }
            val capture = storage.newCaptureFile()
            val image = BitmapBridge.toRgba(bitmap)
            bitmap.recycle()
            if (!BitmapBridge.saveJpeg(image, capture, 95)) {
                failures++
                continue
            }
            val plan = com.kscanner.core.imaging.AutoCrop.plan(
                image,
                workingEdge = 1080,
                fixOrientation = settings.current.autoRotate,
            )
            val filter = if (settings.current.autoFilter) {
                com.kscanner.core.imaging.Filters.auto(image)
            } else {
                settings.defaultFilter
            }
            val scanSettings = ScanProcessor.Settings(
                quad = plan.quad,
                rotationDegrees = plan.rotationDegrees,
                deskewDegrees = if (settings.current.autoDeskew) plan.deskewDegrees else 0.0,
                filter = filter,
                maxOutputEdge = settings.current.maxImageEdge,
            )
            val saved = runCatching { saveCapture(target, capture.absolutePath, scanSettings) }.getOrNull()
            if (saved != null) {
                target = saved
                imported++
            } else {
                failures++
            }
            capture.delete()
        }
        ImportResult(if (imported > 0) target else null, imported, failures)
    }

    private fun decodeBounded(uri: Uri): Bitmap? = runCatching {
        BitmapBridge.decodeUri(context, uri, settings.current.maxImageEdge)?.let { BitmapBridge.toBitmap(it) }
    }.getOrNull()

    // ---------------------------------------------------------------- pages

    suspend fun reprocessPage(
        pageId: Long,
        filter: Int,
        brightness: Float = 0f,
        contrast: Float = 0f,
        sharpness: Float = 0f,
    ): Boolean = withContext(Dispatchers.Default) {
        val found = withContext(Dispatchers.IO) { store.getPage(pageId) } ?: return@withContext false
        val page = ensureReprocessableOriginal(found) ?: return@withContext false
        val source = BitmapBridge.decodeFile(File(page.originalPath), settings.current.maxImageEdge)
            ?: return@withContext false

        val scan = ScanProcessor.Settings(
            quad = Corners.parse(page.corners),
            rotationDegrees = page.rotation,
            filter = ScanFilter.fromId(filter),
            brightness = brightness,
            contrast = contrast,
            sharpness = sharpness,
            maxOutputEdge = settings.current.maxImageEdge,
        )
        val rendered = ScanProcessor.process(source, scan)
        val destination = File(page.processedPath)
        if (!BitmapBridge.saveJpeg(rendered, destination, settings.current.jpegQuality)) {
            return@withContext false
        }
        withContext(Dispatchers.IO) {
            store.updatePageResult(
                pageId = pageId,
                processedPath = destination.absolutePath,
                filterId = scan.filter.id,
                rotation = scan.rotationDegrees,
                corners = page.corners,
                width = rendered.width,
                height = rendered.height,
            )
            refreshThumbnail(page.documentId)
        }
        true
    }

    /**
     * Rotates a finished page clockwise by [degrees] (a multiple of 90) from its current
     * state, keeping its crop aligned.
     *
     * The outline is stored in the coordinate space of the *rotated* original, so it has to
     * be rotated with the same mapping the pixels use; reprocessing then re-crops from the
     * untouched original rather than from an already-compressed render. [ensureReprocessableOriginal]
     * runs first, so the rotation is applied once and never compounds across taps.
     */
    suspend fun rotateBy(pageId: Long, degrees: Int): Boolean = withContext(Dispatchers.Default) {
        val turn = ((degrees % 360) + 360) % 360
        if (turn % 90 != 0) return@withContext false

        val found = withContext(Dispatchers.IO) { store.getPage(pageId) } ?: return@withContext false
        val page = ensureReprocessableOriginal(found) ?: return@withContext false
        val original = BitmapBridge.decodeFile(File(page.originalPath), settings.current.maxImageEdge)
            ?: return@withContext false

        val current = ((page.rotation % 360) + 360) % 360
        val rotated = Transforms.rotate(original, current)
        val existing = Corners.parse(page.corners)

        val nextRotation = (current + turn) % 360
        val nextQuad = rotateQuadBy(existing, turn / 90, rotated.width, rotated.height)

        val scan = ScanProcessor.Settings(
            quad = nextQuad,
            rotationDegrees = nextRotation,
            filter = ScanFilter.fromId(page.filterId),
            maxOutputEdge = settings.current.maxImageEdge,
        )
        val rendered = ScanProcessor.process(original, scan)
        val destination = File(page.processedPath)
        if (!BitmapBridge.saveJpeg(rendered, destination, settings.current.jpegQuality)) {
            return@withContext false
        }
        withContext(Dispatchers.IO) {
            store.updatePageResult(
                pageId = pageId,
                processedPath = destination.absolutePath,
                filterId = page.filterId,
                rotation = nextRotation,
                corners = nextQuad?.let { Corners.serialize(it) },
                width = rendered.width,
                height = rendered.height,
            )
            refreshThumbnail(page.documentId)
        }
        true
    }

    /** Kept for existing callers: one quarter turn in either direction. */
    suspend fun rotatePage(pageId: Long, clockwise: Boolean = true): Boolean =
        rotateBy(pageId, if (clockwise) 90 else 270)

    /** One-tap 180 for a page the automatic pass could not decide. */
    suspend fun flipPage(pageId: Long): Boolean = rotateBy(pageId, 180)

    /**
     * Replaces one page with a fresh capture, in the slot it already occupies.
     *
     * Re-shooting through the strip would otherwise mean deleting the bad page and taking
     * another one, which lands at the end of the document and leaves the user to drag it back.
     * Here the replacement takes the old row's index, so the run's order is untouched, and the
     * old row's files go with it.
     *
     * The pixels are written first and the row swap is one transaction, so the only way to lose
     * the page being fixed is for the write itself to fail — nothing is touched until the
     * replacement has been written out in full.
     *
     * @return the row id of the replacement, or null when the page is gone or its pixels could
     *   not be written
     * @throws IllegalStateException if the row could not be swapped, in which case the original
     *   page and its files are still exactly as they were
     */
    suspend fun retakePage(pageId: Long, replacement: RenderedPage): Long? =
        withContext(Dispatchers.Default) {
            val previous = withContext(Dispatchers.IO) { store.getPage(pageId) }
                ?: return@withContext null
            val documentId = previous.documentId

            val pageFile = withContext(Dispatchers.IO) { storage.newPageFile(documentId, "page") }
            if (!BitmapBridge.saveJpeg(replacement.image, pageFile, settings.current.jpegQuality)) {
                return@withContext null
            }

            val originalFile = withContext(Dispatchers.IO) {
                val source = replacement.originalPath
                if (settings.current.keepOriginals && source != null) {
                    val dest = storage.newPageFile(documentId, "original")
                    if (BitmapBridge.copyToFile(File(source).inputStream(), dest)) dest else null
                } else {
                    null
                }
            }

            val newId = try {
                withContext(Dispatchers.IO) {
                    store.replacePage(
                        pageId = pageId,
                        documentId = documentId,
                        index = previous.index,
                        originalPath = (originalFile ?: pageFile).absolutePath,
                        processedPath = pageFile.absolutePath,
                        filterId = replacement.filterId,
                        rotation = replacement.rotation,
                        corners = replacement.quad?.let { Corners.serialize(it) },
                        width = replacement.image.width,
                        height = replacement.image.height,
                        flipDecided = replacement.flipDecided,
                    )
                }
            } catch (failure: Exception) {
                // The row swap was rolled back, so the page being fixed is intact, but the files
                // just written are not referenced by anything: they go with the failure.
                withContext(Dispatchers.IO) {
                    storage.deleteFile(pageFile.absolutePath)
                    storage.deleteFile(originalFile?.absolutePath)
                }
                throw failure
            }

            // Only once the row is in place: the files the old row pointed at are the last copy
            // of what was there, so they go after the swap and never before it.
            withContext(Dispatchers.IO) {
                storage.deleteFile(previous.processedPath)
                // The two paths are the same file for a page written with `keepOriginals` off,
                // which the first delete has already removed and which must not be freed twice.
                if (previous.originalPath != previous.processedPath) {
                    storage.deleteFile(previous.originalPath)
                }
            }

            if (settings.current.autoOcr) {
                runCatching { runOcrForPage(documentId, previous.index) }
            }
            withContext(Dispatchers.IO) { refreshThumbnail(documentId) }
            newId
        }

    /**
     * Guarantees [page]'s original holds pixels with no rotation baked in.
     *
     * A page whose original *is* its processed file — a book half, an ID-card sheet, or any
     * page written with `keepOriginals` off — would otherwise have its stored rotation applied
     * to pixels that already carry it, so the page turns twice per tap and never comes back.
     * Such a page first gets its current pixels frozen into a new original, once; every later
     * rotation or filter change is then relative to those frozen pixels, which is also what
     * makes four quarter turns return the page to where it started.
     *
     * @return the page to work from, or null when the frozen original could not be written
     */
    private suspend fun ensureReprocessableOriginal(page: Page): Page? {
        if (page.originalPath != page.processedPath) return page

        val frozen = withContext(Dispatchers.IO) { storage.newPageFile(page.documentId, "original") }
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                val source = File(page.processedPath)
                if (source.exists()) {
                    BitmapBridge.copyToFile(source.inputStream(), frozen)
                } else {
                    false
                }
            }.getOrDefault(false)
        }
        if (!copied) return null

        withContext(Dispatchers.IO) {
            store.setPageOriginal(
                pageId = page.id,
                originalPath = frozen.absolutePath,
                rotation = 0,
                corners = null,
            )
        }
        return page.copy(originalPath = frozen.absolutePath, rotation = 0, corners = null)
    }

    /** [quad] rotated [times] quarter turns clockwise, tracking the frame it lives in. */
    private fun rotateQuadBy(quad: Quad?, times: Int, width: Int, height: Int): Quad? {
        if (quad == null) return null
        var current: Quad = quad
        var frameWidth = width
        var frameHeight = height
        repeat(((times % 4) + 4) % 4) {
            current = Transforms.rotateQuad90(current, frameWidth, frameHeight)
            val swap = frameWidth
            frameWidth = frameHeight
            frameHeight = swap
        }
        return current
    }

    suspend fun deletePage(pageId: Long): Long? = withContext(Dispatchers.IO) {
        val page = store.getPage(pageId) ?: return@withContext null
        storage.deleteFile(page.processedPath)
        if (page.originalPath != page.processedPath) storage.deleteFile(page.originalPath)
        store.deletePage(pageId)

        val remaining = store.getPages(page.documentId)
        if (remaining.isEmpty()) {
            store.deleteDocument(page.documentId)
            storage.deleteDocument(page.documentId)
            null
        } else {
            store.reorderPages(page.documentId, remaining.map { it.id })
            refreshThumbnail(page.documentId)
            page.documentId
        }
    }

    suspend fun reorderPages(documentId: Long, orderedPageIds: List<Long>) = withContext(Dispatchers.IO) {
        store.reorderPages(documentId, orderedPageIds)
        refreshThumbnail(documentId)
    }

    /** Regenerates the library thumbnail and cached size for a document. */
    suspend fun refreshThumbnail(documentId: Long) = withContext(Dispatchers.IO) {
        val pages = store.getPages(documentId)
        val first = pages.firstOrNull()
        if (first == null) {
            store.refreshDocumentStats(documentId, null, 0)
            return@withContext
        }
        val thumbnail = storage.thumbnailFile(documentId)
        val decoded = BitmapBridge.decodeFile(File(first.processedPath), 512)
        val written = if (decoded != null) {
            val small = Transforms.fitLongestEdge(decoded, 420)
            BitmapBridge.saveJpeg(small, thumbnail, 82)
        } else {
            false
        }
        store.refreshDocumentStats(
            id = documentId,
            coverPath = if (written) thumbnail.absolutePath else first.processedPath,
            sizeBytes = storage.documentSize(documentId),
        )
    }

    // ---------------------------------------------------------------- OCR

    /** Runs OCR on a page and stores both the plain text and the line geometry. */
    suspend fun runOcrForPage(documentId: Long, pageIndex: Int): String? {
        val page = pages(documentId).getOrNull(pageIndex) ?: return null
        return runOcr(page.id)
    }

    suspend fun runOcr(pageId: Long): String? = withContext(Dispatchers.Default) {
        val page = withContext(Dispatchers.IO) { store.getPage(pageId) } ?: return@withContext null
        val image = BitmapBridge.decodeFile(File(page.processedPath), 3200) ?: return@withContext null
        val bitmap = BitmapBridge.toBitmap(image)
        val lines = try {
            ocr.recognize(bitmap)
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        } ?: return@withContext null

        val text = OcrText.plainText(lines)
        withContext(Dispatchers.IO) {
            store.setPageOcr(pageId, text, OcrText.encode(lines))
        }
        text
    }

    // ---------------------------------------------------------------- export

    /**
     * Writes the document's recognised text to a plain `.txt` file.
     *
     * Text is only as useful as it is portable: a scan that has been OCR'd should be
     * quotable, mailable and greppable without a PDF reader. Pages are separated by a ruled
     * line so a reader can tell where one ended.
     */
    suspend fun exportText(documentId: Long): File? = withContext(Dispatchers.IO) {
        val document = store.getDocument(documentId) ?: return@withContext null
        val pages = store.getPages(documentId)
        val body = pages.mapNotNull { it.ocrText?.trim()?.takeIf { text -> text.isNotEmpty() } }
        if (body.isEmpty()) return@withContext null

        val destination = storage.uniqueExportFile(document.name, "txt")
        destination.writeText(body.joinToString("\n\n────────\n\n"))
        destination
    }

    suspend fun exportPdf(documentId: Long): File? = withContext(Dispatchers.Default) {
        val document = withContext(Dispatchers.IO) { store.getDocument(documentId) } ?: return@withContext null
        val pages = withContext(Dispatchers.IO) { store.getPages(documentId) }
        if (pages.isEmpty()) return@withContext null

        val sources = pages.map { page ->
            PdfExporter.PageSource(
                jpegFile = File(page.processedPath),
                width = page.width,
                height = page.height,
                ocrLines = OcrText.decode(page.ocrLines),
            )
        }

        val pageSize = when (settings.current.pdfPageSize) {
            PdfPageSize.A4 -> PdfWriter.PageSize.A4
            PdfPageSize.LETTER -> PdfWriter.PageSize.LETTER
            PdfPageSize.FIT_TO_IMAGE -> PdfWriter.PageSize.FIT_IMAGE
        }

        val destination = withContext(Dispatchers.IO) { storage.uniqueExportFile(document.name, "pdf") }
        PdfExporter.export(
            pages = sources,
            destination = destination,
            pageSize = pageSize,
            includeText = settings.current.pdfSearchableText,
        )?.let { destination }
    }

    // ---------------------------------------------------------------- sharing

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun sharePdf(file: File): Intent {
        val uri = uriFor(file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareImages(files: List<File>): Intent {
        val uris = ArrayList(files.map { uriFor(it) })
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareText(text: String, subject: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }

    fun defaultDocumentName(): String =
        "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())

    /** Drop-in helper for the capture screen: decodes a preview-sized copy. */
    suspend fun loadForEditing(path: String, maxEdge: Int = 2400): RgbaImage? = withContext(Dispatchers.Default) {
        BitmapBridge.decodeFile(File(path), maxEdge)
    }
}