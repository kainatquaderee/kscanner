package com.kscanner.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kscanner.data.Document
import com.kscanner.data.Page
import com.kscanner.data.ScanRepository
import com.kscanner.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocumentViewModel(
    private val repository: ScanRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    var document by mutableStateOf<Document?>(null)
        private set

    var pages by mutableStateOf<List<Page>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var busy by mutableStateOf(false)
        private set

    var busyLabel by mutableStateOf("Working…")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Currently shown OCR output, with the page it came from. */
    var ocrText by mutableStateOf<String?>(null)
        private set

    var ocrCaption by mutableStateOf("")
        private set

    private var documentId: Long = -1L

    val settingsState = settings.state

    fun load(id: Long) {
        documentId = id
        loading = true
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                repository.getDocument(id) to repository.pages(id)
            }
            document = loaded.first
            pages = loaded.second
            loading = false
        }
    }

    fun refresh() {
        if (documentId < 0) return
        load(documentId)
    }

    fun rename(name: String) {
        val id = documentId
        if (id < 0 || name.isBlank()) return
        viewModelScope.launch {
            repository.renameDocument(id, name)
            document = repository.getDocument(id)
        }
    }

    fun deletePage(page: Page, onDocumentEmpty: () -> Unit) {
        viewModelScope.launch {
            busy = true
            busyLabel = "Deleting page…"
            val remaining = withContext(Dispatchers.IO) { repository.deletePage(page.id) }
            busy = false
            if (remaining == null) {
                onDocumentEmpty()
            } else {
                refresh()
            }
        }
    }

    fun rotatePage(page: Page, clockwise: Boolean = true) {
        viewModelScope.launch {
            busy = true
            busyLabel = "Rotating…"
            val ok = repository.rotatePage(page.id, clockwise)
            busy = false
            if (!ok) error = "That page could not be rotated."
            refresh()
        }
    }

    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch {
            repository.reorderPages(documentId, orderedIds)
            refresh()
        }
    }

    fun runOcr(page: Page) {
        viewModelScope.launch {
            busy = true
            busyLabel = "Recognising text…"
            val text = repository.runOcr(page.id)
            busy = false
            if (text.isNullOrBlank()) {
                error = "No readable text was found on that page."
            } else {
                ocrText = text
                ocrCaption = "Page ${page.index + 1}"
                refresh()
            }
        }
    }

    fun clearOcr() {
        ocrText = null
        ocrCaption = ""
    }

    fun export(onReady: (File) -> Unit) {
        viewModelScope.launch {
            busy = true
            busyLabel = "Building PDF…"
            val file = repository.exportPdf(documentId)
            busy = false
            if (file != null) onReady(file) else error = "The PDF could not be created."
        }
    }

    /** Recognised text of every page, in order. Empty when nothing has been OCR'd. */
    val combinedText: String
        get() = pages.mapNotNull { it.ocrText?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n────────\n\n")

    val hasText: Boolean get() = pages.any { !it.ocrText.isNullOrBlank() }

    /** Writes the recognised text to a `.txt` file and hands it to [onReady]. */
    fun exportText(onReady: (File) -> Unit) {
        viewModelScope.launch {
            busy = true
            busyLabel = "Writing text…"
            val file = repository.exportText(documentId)
            busy = false
            if (file != null) {
                onReady(file)
            } else {
                error = "No recognised text to export yet."
            }
        }
    }

    fun allPageImages(): List<File> = pages.map { File(it.processedPath) }.filter { it.exists() }

    fun shareIntentForPages() = repository.shareImages(allPageImages())

    fun deleteDocument(onDone: () -> Unit) {
        viewModelScope.launch {
            busy = true
            busyLabel = "Deleting…"
            withContext(Dispatchers.IO) { repository.deleteDocument(documentId) }
            busy = false
            onDone()
        }
    }

    fun clearError() {
        error = null
    }
}