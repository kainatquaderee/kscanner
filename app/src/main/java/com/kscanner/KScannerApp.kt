package com.kscanner

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kscanner.core.ocr.OcrEngine
import com.kscanner.ui.DocumentViewModel
import com.kscanner.ui.EditorViewModel
import com.kscanner.ui.LibraryViewModel
import com.kscanner.ui.SettingsViewModel
import com.kscanner.data.DocumentStore
import com.kscanner.data.ScanRepository
import com.kscanner.data.SettingsStore
import com.kscanner.data.StorageManager

/** Process-wide singletons. Created once, never re-created on configuration change. */
class AppContainer(context: Context) {
    val store = DocumentStore(context)
    val storage = StorageManager(context)
    val settings = SettingsStore(context)
    private val ocr = OcrEngine()
    val repository = ScanRepository(context, store, storage, settings, ocr)
}

class KScannerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Builds view models with the application container injected. */
class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
                LibraryViewModel(container.repository, container.store, container.settings)

            modelClass.isAssignableFrom(EditorViewModel::class.java) ->
                EditorViewModel(container.repository, container.settings)

            modelClass.isAssignableFrom(DocumentViewModel::class.java) ->
                DocumentViewModel(container.repository, container.settings)

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(container.settings)

            else -> throw IllegalArgumentException("unknown view model ${modelClass.name}")
        }
        return viewModel as T
    }
}