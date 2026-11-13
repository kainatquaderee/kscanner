package com.kscanner.ui

import android.net.Uri
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

    var query: String = ""
        private set
    var sort: DocumentSort = DocumentSort.DATE_DESC
        private set
    var folder: String? = null
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

    fun setQuery(value: String) {
        if (query == value) return
        query = value
        reload()
    }

    fun setSort(value: DocumentSort) {
        if (sort == value) return
        sort = value
        reload()
    }

    fun setFolder(value: String?) {
        if (folder == value) return
        folder = value
        reload()
    }

    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ids.forEach { repository.deleteDocument(it) } }
            reload()
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repository.renameDocument(id, name)
            reload()
        }
    }

    /** Imports gallery images as a new document. Returns its id, or null on failure. */
    suspend fun repositoryImport(uris: List<Uri>): Long? {
        val id = withContext(Dispatchers.IO) { repository.importImages(uris) }
        reload()
        return id
    }

    fun createBlankDocument(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createDocument()
            onCreated(id)
            reload()
        }
    }
}