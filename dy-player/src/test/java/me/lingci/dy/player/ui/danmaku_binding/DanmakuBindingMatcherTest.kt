package me.lingci.dy.player.ui.danmaku_binding

import me.lingci.dy.player.entity.VideoData
import me.lingci.lib.base.storage.entity.FileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuBindingMatcherTest {

    @Test
    fun sameEpisode_withDifferentNotation_isAutoBindable() {
        val candidates = match(
            "进击的巨人.S01E01.1080p.WEB-DL.mkv",
            "进击的巨人.第01集.1080p.xml"
        )

        assertTrue(candidates.single().autoBindable)
        assertTrue(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun yearPrefix_doesNotHideDifferentEpisodes() {
        val candidates = match("Show.2024.01.mkv", "Show.2024.02.xml")

        assertNull(candidates.single().episodeSame)
        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun differentSeason_blocksAutoBind() {
        val candidates = match("Show.S01E01.mkv", "Show.S02E01.xml")

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun conflictingSeasonHints_blockAutoBind() {
        val candidates = DanmakuBindingMatcher.match(
            video("Show.E01.mkv"),
            listOf(file("Show.E01.xml")),
            videoSeasonHint = 1,
            fileSeasonHint = 2
        )

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun matchingSeasonHints_allowAutoBind() {
        val candidates = DanmakuBindingMatcher.match(
            video("Show.E01.mkv"),
            listOf(file("Show.E01.xml")),
            videoSeasonHint = 1,
            fileSeasonHint = 1
        )

        assertTrue(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun episodeRange_isNeverAutoBound() {
        val candidates = match("Show.E01.mkv", "Show.E01-E02.xml")

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun equalTopCandidates_requireManualConfirmation() {
        val candidates = match(
            "Show.E01.mkv",
            "Show.E01.xml",
            "Show.E01.ass"
        )

        assertTrue(candidates.all { it.autoBindable })
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun equalScores_useNaturalFileNameOrder() {
        val candidates = match(
            "Show.mkv",
            "Show.E10.xml",
            "Show.E02.xml"
        )

        assertEquals(listOf("Show.E02.xml", "Show.E10.xml"), candidates.map { it.file.name })
    }

    @Test
    fun chineseEpisodeNumber_isRecognized() {
        val candidates = match("Show.E12.mkv", "Show.第十二集.xml")

        assertTrue(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun singleBareEpisodeNumber_isRecognized() {
        val candidates = match("Show.01.mkv", "Show.E01.xml")

        assertTrue(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun sameEpisodeWithoutTitleEvidence_isNotAutoBound() {
        val candidates = match("Alpha.E01.mkv", "Beta.E01.xml")

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun extraSequelTitleToken_blocksAutoBind() {
        val candidates = match("进击的巨人.S01E01.mkv", "进击的巨人.最终季.E01.xml")

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun seasonZero_isARealSeason() {
        val candidates = DanmakuBindingMatcher.match(
            video("Show.E01.mkv"),
            listOf(file("Show.E01.xml")),
            videoSeasonHint = 0,
            fileSeasonHint = 1
        )

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun adjacentEpisodeMarkers_areTreatedAsMultipleEpisodes() {
        val candidates = match("Show.E01.mkv", "Show.E01E02.xml")

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    @Test
    fun adjacentSeasonEpisodeMarkers_areTreatedAsMultipleEpisodes() {
        val candidates = match("Show.S01E01.mkv", "Show.S01E01E02.xml")

        assertFalse(candidates.single().autoBindable)
        assertFalse(DanmakuBindingMatcher.shouldAutoBind(candidates))
    }

    private fun match(videoName: String, vararg fileNames: String): List<DanmakuBindingMatcher.Candidate> {
        return DanmakuBindingMatcher.match(video(videoName), fileNames.map(::file))
    }

    private fun video(name: String) = VideoData(name = name, videoUrl = "/videos/$name")

    private fun file(name: String) = FileEntity(name = name, title = name, path = "/danmaku/$name")
}
