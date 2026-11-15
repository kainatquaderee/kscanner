package com.kscanner.data

/**
 * What a single capture is turned into.
 *
 * This is the *shape* of one shot and nothing else. How many shots a run takes is the Batch
 * toggle's business: [SINGLE] with Batch on is rapid capture of page after page, while
 * [SINGLE] with Batch off hands the one shot to the editor so the corners can be placed by
 * hand. Keeping the two apart is what makes "a spread, and keep going" expressible.
 *
 * [BOOK] and [ID_CARD] skip the editor because the useful work is mechanical: a spread
 * splits itself in two, and a card's two sides stack onto one sheet.
 */
enum class ScanMode(
    val label: String,
    val hint: String,
) {
    SINGLE("Single", "Crop and enhance one shot"),
    BOOK("Book", "One spread becomes two pages"),
    ID_CARD("ID Card", "Front and back on one sheet"),

    ;

    /** ID-1 card ratio (ISO/IEC 7810), used to steer detection. */
    val aspectHint: Double? get() = if (this == ID_CARD) 1.586 else null

    /**
     * True when one shot yields exactly one page, and so a page can be replaced by a single
     * re-shoot. False for a spread (one shot, two pages) and a card (two shots, one page).
     */
    val isOnePagePerShot: Boolean get() = this == SINGLE

    companion object {

        /**
         * Reads a stored mode left behind by an older version, and the Batch setting it
         * implies.
         *
         * Batch was a mode of its own named `BATCH` up to 1.4.0, meaning the single-page
         * shape captured over and over, so it decodes to [SINGLE] with Batch on rather than
         * being dropped: reading it back as plain [SINGLE] would quietly turn a user's
         * rapid-capture habit into a one-shot-to-the-editor one.
         *
         * `BOOK` and `ID_CARD` were likewise always continuous, so they imply Batch on too.
         *
         * @param raw the stored enum name, or null when nothing was ever written
         * @return the shape to use, and the Batch toggle that value implies
         */
        fun decodeStored(raw: String?): Pair<ScanMode, Boolean> = when (raw) {
            BOOK.name -> BOOK to true
            ID_CARD.name -> ID_CARD to true
            SINGLE.name -> SINGLE to false
            LEGACY_BATCH_NAME -> SINGLE to true
            // Unreadable or absent: the one-shot shape, which is what a first run wants and
            // the least surprising answer for a value this version cannot name.
            else -> SINGLE to false
        }

        /** The name `BATCH` held in preferences until the toggle replaced it. */
        private const val LEGACY_BATCH_NAME = "BATCH"
    }
}
