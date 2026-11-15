package com.kscanner.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kscanner.data.Document
import com.kscanner.data.DocumentSort
import com.kscanner.data.DocumentStore
import com.kscanner.data.ScanRepository
import com.kscanner.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(
    private val repository: ScanRepository,
    private val store: DocumentStore,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents = _documents.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders = _folders.asStateFlow()

    val settingsState = settings.state

    /**
     * The one source of the library's filters. These were mirrored by `remember`ed state in
     * `LibraryScreen`, which let the search box and the query it ran drift apart.
     */
    var query by mutableStateOf("")
        private set
    var sort by mutableStateOf(DocumentSort.DATE_DESC)
        private set
    var folder by mutableStateOf<String?>(null)
        private set

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                store.listDocuments(query, folder, sort) to store.listFolders()
            }
            _documents.value = result.first
            _folders.value = result.second
            _loading.value = false
        }
    }

    fun updateQuery(value: String) {
        if (query == value) return
        query = value
        reload()
    }

    fun updateSort(value: DocumentSort) {
        if (sort == value) return
        sort = value
        reload()
    }

    fun updateFolder(value: String?) {
        if (folder == value) return
        folder = value
        reload()
    }

    /**
     * Deletes [ids] and reports how many documents actually went, so the caller can say
     * "3 documents deleted" instead of leaving the user to infer it from a grid that shrank.
     */
    fun delete(ids: Collection<Long>, onDeleted: (Int) -> Unit = {}) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                var count = 0
                for (id in ids) {
                    if (runCatching { repository.deleteDocument(id) }.isSuccess) count++
                }
                count
            }
            reload()
            onDeleted(removed)
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repository.renameDocument(id, name)
            reload()
        }
    }

    /** Imports gallery images as a new document, reporting what got through. */
    suspend fun repositoryImport(uris: List<Uri>): ScanRepository.ImportResult {
        val result = withContext(Dispatchers.IO) { repository.importImages(uris) }
        reload()
        return result
    }

    fun createBlankDocument(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createDocument()
            onCreated(id)
            reload()
        }
    }
}