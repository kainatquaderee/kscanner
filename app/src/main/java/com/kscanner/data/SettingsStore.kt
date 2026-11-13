package com.kscanner.data

import android.content.Context
import com.kscanner.core.imaging.ScanFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class PdfPageSize(val label: String) {
    A4("A4"),
    LETTER("Letter"),
    FIT_TO_IMAGE("Fit image"),
}

data class AppSettings(
    val defaultFilterId: Int = ScanFilter.MAGIC_COLOR.id,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val jpegQuality: Int = 88,
    val maxImageEdge: Int = 2600,
    val pdfPageSize: PdfPageSize = PdfPageSize.A4,
    val pdfSearchableText: Boolean = true,
    val autoOcr: Boolean = false,
    val keepOriginals: Boolean = true,
    val autoCapture: Boolean = false,
)

/**
 * Preference-backed settings exposed as a [StateFlow] so Compose screens recompose
 * when a value changes.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("kscanner.settings", Context.MODE_PRIVATE)

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = mutable.asStateFlow()

    val current: AppSettings get() = mutable.value
    val defaultFilter: ScanFilter get() = ScanFilter.fromId(current.defaultFilterId)

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        persist(next)
        mutable.value = next
    }

    private fun load(): AppSettings = AppSettings(
        defaultFilterId = prefs.getInt(KEY_FILTER, AppSettings().defaultFilterId),
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM),
        jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, AppSettings().jpegQuality),
        maxImageEdge = prefs.getInt(KEY_MAX_EDGE, AppSettings().maxImageEdge),
        pdfPageSize = runCatching {
            PdfPageSize.valueOf(prefs.getString(KEY_PDF_SIZE, PdfPageSize.A4.name)!!)
        }.getOrDefault(PdfPageSize.A4),
        pdfSearchableText = prefs.getBoolean(KEY_PDF_TEXT, true),
        autoOcr = prefs.getBoolean(KEY_AUTO_OCR, false),
        keepOriginals = prefs.getBoolean(KEY_KEEP_ORIGINALS, true),
        autoCapture = prefs.getBoolean(KEY_AUTO_CAPTURE, false),
    )

    private fun persist(settings: AppSettings) {
        prefs.edit()
            .putInt(KEY_FILTER, settings.defaultFilterId)
            .putString(KEY_THEME, settings.themeMode.name)
            .putInt(KEY_JPEG_QUALITY, settings.jpegQuality)
            .putInt(KEY_MAX_EDGE, settings.maxImageEdge)
            .putString(KEY_PDF_SIZE, settings.pdfPageSize.name)
            .putBoolean(KEY_PDF_TEXT, settings.pdfSearchableText)
            .putBoolean(KEY_AUTO_OCR, settings.autoOcr)
            .putBoolean(KEY_KEEP_ORIGINALS, settings.keepOriginals)
            .putBoolean(KEY_AUTO_CAPTURE, settings.autoCapture)
            .apply()
    }

    private companion object {
        const val KEY_FILTER = "default_filter"
        const val KEY_THEME = "theme_mode"
        const val KEY_JPEG_QUALITY = "jpeg_quality"
        const val KEY_MAX_EDGE = "max_image_edge"
        const val KEY_PDF_SIZE = "pdf_page_size"
        const val KEY_PDF_TEXT = "pdf_searchable_text"
        const val KEY_AUTO_OCR = "auto_ocr"
        const val KEY_KEEP_ORIGINALS = "keep_originals"
        const val KEY_AUTO_CAPTURE = "auto_capture"
    }
}
