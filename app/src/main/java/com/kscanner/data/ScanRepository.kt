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
    )

    /**
     * Writes already-rendered pages to a document, creating the document first when
     * [documentId] is null.
     *
     * @return the document id the pages were added to
     */
    suspend fun appendPages(
        documentId: Long?,
        pages: List<RenderedPage>,
        documentName: String? = null,
    ): Long = withContext(Dispatchers.Default) {
        require(pages.isNotEmpty()) { "nothing to write" }

        val id = documentId ?: withContext(Dispatchers.IO) {
            store.insertDocument(documentName ?: defaultDocumentName(), null)
        }

        var index = withContext(Dispatchers.IO) { store.countPages(id) }
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
                )
            }

            if (settings.current.autoOcr) {
                runCatching { runOcrForPage(id, index) }
            }
            index++
        }

        withContext(Dispatchers.IO) { refreshThumbnail(id) }
        id
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
        )
    }

    /** Imports gallery images, each becoming its own page with automatic edge detection. */
    suspend fun importImages(uris: List<Uri>, documentId: Long? = null): Long? = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext documentId

        var target = documentId
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
            target = runCatching { saveCapture(target, capture.absolutePath, scanSettings) }
                .getOrElse { failures++; target }
            capture.delete()
        }
        if (failures == uris.size) null else target
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
        val page = withContext(Dispatchers.IO) { store.getPage(pageId) } ?: return@withContext false
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
     * Rotates a finished page by a quarter turn, keeping its crop aligned.
     *
     * The outline is stored in the coordinate space of the *rotated* original, so it has to
     * be rotated with the same mapping the pixels use; reprocessing then re-crops from the
     * untouched original rather than from an already-compressed render.
     */
    suspend fun rotatePage(pageId: Long, clockwise: Boolean = true): Boolean = withContext(Dispatchers.Default) {
        val page = withContext(Dispatchers.IO) { store.getPage(pageId) } ?: return@withContext false
        val original = BitmapBridge.decodeFile(File(page.originalPath), settings.current.maxImageEdge)
            ?: return@withContext false

        val current = ((page.rotation % 360) + 360) % 360
        val rotated = Transforms.rotate(original, current)
        val existing = Corners.parse(page.corners)

        val nextRotation = if (clockwise) (current + 90) % 360 else (current + 270) % 360
        val nextQuad = when {
            existing == null -> null
            clockwise -> Transforms.rotateQuad90(existing, rotated.width, rotated.height)
            else -> {
                // Three clockwise turns are the same as one counter-clockwise turn.
                val once = Transforms.rotateQuad90(
                    Transforms.rotateQuad90(
                        Transforms.rotateQuad90(existing, rotated.width, rotated.height),
                        rotated.height, rotated.width,
                    ),
                    rotated.width, rotated.height,
                )
                once
            }
        }

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