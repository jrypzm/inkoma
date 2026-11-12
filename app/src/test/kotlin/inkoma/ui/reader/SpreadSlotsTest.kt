package inkoma.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import inkoma.domain.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals

class SpreadSlotsTest {
    private val ch = Chapter(1, 1, "/c", "Ch 1", null, false, false, 0, 1.0, 0, 0, 0, 0)
    private val next = ch.copy(id = 2, name = "Ch 2")

    private fun items(pages: Int): List<ReaderItem> =
        listOf<ReaderItem>(ReaderItem.Transition(ch, null, false)) +
            (0 until pages).map { ReaderItem.PageItem(ch, Page(it), pages) } +
            ReaderItem.Transition(ch, next, true)

    @Test
    fun noSpreadIsOneItemPerSlot() {
        assertEquals(List(7) { listOf(it) }, buildSlots(items(5), spread = false, shift = false))
    }

    @Test
    fun coverAloneThenPairs() {
        // [transition] [p0] [p1 p2] [p3 p4] [p5] [transition]
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5), listOf(6), listOf(7)), buildSlots(items(6), spread = true, shift = false))
    }

    @Test
    fun shiftPairsFromFirstPage() {
        // [transition] [p0 p1] [p2 p3] [p4] [transition]
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5), listOf(6)), buildSlots(items(5), spread = true, shift = true))
    }
}
