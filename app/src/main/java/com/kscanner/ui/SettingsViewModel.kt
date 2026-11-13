package com.kscanner.ui

import androidx.lifecycle.ViewModel
import com.kscanner.data.AppSettings
import com.kscanner.data.SettingsStore
import com.kscanner.data.ThemeMode

class SettingsViewModel(private val settings: SettingsStore) : ViewModel() {

    val state get() = settings.state

    fun setDefaultFilter(id: Int) = settings.update { it.copy(defaultFilterId = id) }

    fun setThemeMode(mode: ThemeMode) = settings.update { it.copy(themeMode = mode) }

    fun setJpegQuality(quality: Int) = settings.update { it.copy(jpegQuality = quality.coerceIn(60, 100)) }

    fun setMaxImageEdge(edge: Int) = settings.update { it.copy(maxImageEdge = edge.coerceIn(1200, 4000)) }

    fun setPdfPageSize(size: com.kscanner.data.PdfPageSize) = settings.update { it.copy(pdfPageSize = size) }

    fun setSearchableText(enabled: Boolean) = settings.update { it.copy(pdfSearchableText = enabled) }

    fun setAutoOcr(enabled: Boolean) = settings.update { it.copy(autoOcr = enabled) }

    fun setKeepOriginals(enabled: Boolean) = settings.update { it.copy(keepOriginals = enabled) }

    fun current(): AppSettings = settings.current
}