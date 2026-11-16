package com.kscanner.data

/**
 * The rules and the words behind "Apply to all": which pages a look goes to, whether the action is
 * offered at all, and what the confirmations and the result say.
 *
 * It is separated from the editor so the wording that tells someone how many pages they are about
 * to change, and that tells them what actually happened, can be checked without a device: the
 * counts on screen are the only evidence a partial run leaves behind, and a summary that rounded
 * them or claimed an untouched document would misreport work that did happen.
 */
object ApplyLook {

    /**
     * Whether the action is offered.
     *
     * It needs a page that exists in a document — the run works through stored rows, and a capture
     * that has not been saved yet has no row and no others to change — and at least one other page
     * to copy onto. A one-page document has nothing to apply to.
     */
    fun isAvailable(pageCount: Int, editingPageId: Long): Boolean =
        editingPageId >= 0 && pageCount >= 2

    /** Every page of the document except the one open in the editor. */
    fun targets(pages: List<Page>, editingPageId: Long): List<Page> =
        pages.filter { it.id != editingPageId }

    /** "Apply this look to the other 10 pages?" */
    fun confirmation(count: Int): String = "Apply this look to the other ${pages(count)}?"

    /**
     * "Applying page 3 of 11..." — the page's own number within the document and the document's
     * length, so the count matches the numbers the viewer shows.
     */
    fun progress(pageNumber: Int, total: Int): String = "Applying page $pageNumber of $total..."

    /**
     * What the run achieved, in the order it happened.
     *
     * A cancelled run is *not* a failed one and is never described as one: the pages already
     * rewritten stay rewritten, so the summary says how far it got before it says what landed.
     * Nothing is claimed when nothing happened.
     */
    fun summary(applied: Int, failed: Int, cancelled: Boolean, total: Int): String {
        val parts = ArrayList<String>(3)
        if (cancelled) {
            val reached = applied + failed
            parts.add(
                if (reached == 0) "Cancelled. No pages were changed." else "Stopped after $reached of $total pages.",
            )
        }
        if (applied > 0) parts.add("The look was applied to ${pages(applied)}.")
        if (failed > 0) parts.add("${pages(failed)} could not be updated.")
        if (parts.isEmpty()) parts.add("No pages were changed.")
        return parts.joinToString(" ")
    }

    private fun pages(count: Int): String = if (count == 1) "1 page" else "$count pages"
}