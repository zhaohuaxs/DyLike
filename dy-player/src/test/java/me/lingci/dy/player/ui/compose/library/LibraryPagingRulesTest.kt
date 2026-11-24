package me.lingci.dy.player.ui.compose.library

import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPagingRulesTest {

    @Test
    fun representativeChange_keepsStableCardKey() {
        val first = MediaUiModel(id = "s01", title = "S01", type = MediaLibType.LOCAL, seriesKey = "series")
        val second = first.copy(id = "s02", title = "S02")

        assertEquals("series:series", first.cardKey)
        assertEquals(first.cardKey, second.cardKey)
    }

    @Test
    fun standaloneCard_usesMediaId() {
        val media = MediaUiModel(id = "media", title = "Media", type = MediaLibType.LOCAL)

        assertEquals("media:media", media.cardKey)
    }

    @Test
    fun likePattern_escapesWildcardsAndEscapeCharacter() {
        assertEquals("100\\% Pure", escapeLikePattern("100% Pure"))
        assertEquals("Under\\_Score", escapeLikePattern("Under_Score"))
        assertEquals("Back\\\\Slash", escapeLikePattern("Back\\Slash"))
    }
}
