package com.kscanner.data

/**
 * What a capture is turned into.
 *
 * [SINGLE] hands the shot to the editor, which is right when the user wants to place the
 * corners themselves. The others skip the editor because the useful work is mechanical:
 * they append straight to a document and keep the camera ready for the next shot.
 */
enum class ScanMode(
    val label: String,
    val hint: String,
) {
    SINGLE("Single", "Crop and enhance one shot"),
    BATCH("Batch", "Rapid pages into one document"),
    BOOK("Book", "One spread becomes two pages"),
    ID_CARD("ID Card", "Front and back on one sheet"),

    ;

    /** Modes that keep the camera open and write pages directly. */
    val isContinuous: Boolean get() = this != SINGLE

    /** ID-1 card ratio (ISO/IEC 7810), used to steer detection. */
    val aspectHint: Double? get() = if (this == ID_CARD) 1.586 else null
}
