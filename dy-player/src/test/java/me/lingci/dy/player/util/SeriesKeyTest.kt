package me.lingci.dy.player.util

import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesKeyTest {

    @Test
    fun seasonFolderNames_areRecognized() {
        assertEquals("S01", SeriesKey.seasonLabelOf("/storage/emulated/0/剧集/S01"))
        assertEquals("Season 01", SeriesKey.seasonLabelOf("https://host/dav/剧集/Season 01"))
        assertEquals("第 1 季", SeriesKey.seasonLabelOf("/剧集/第 1 季"))
        assertTrue(SeriesKey.seasonLabelOf("/剧集/E01").isEmpty())
    }

    @Test
    fun sameSeries_sharesKey_regardlessOfSeason() {
        val s01 = SeriesKey.seriesKeyOf("/storage/emulated/0/剧集/S01", MediaLibType.LOCAL, null)
        val s02 = SeriesKey.seriesKeyOf("/storage/emulated/0/剧集/S02", MediaLibType.LOCAL, null)
        val other = SeriesKey.seriesKeyOf("/storage/emulated/0/别的剧/S01", MediaLibType.LOCAL, null)
        assertTrue(s01.isNotBlank())
        assertEquals(s01, s02)
        assertNotEquals(s01, other)
    }

    @Test
    fun plainFolder_hasNoSeriesKey() {
        assertTrue(
            SeriesKey.seriesKeyOf("/storage/emulated/0/电影", MediaLibType.LOCAL, null).isEmpty()
        )
    }

    @Test
    fun seasonNumber_isNaturalOrder_andTitleComesFromParent() {
        assertTrue(SeriesKey.seasonNumberOf("S10") > SeriesKey.seasonNumberOf("S09"))
        assertEquals("剧集", SeriesKey.seriesTitleOf("/storage/emulated/0/剧集/S01"))
    }

    @Test
    fun chineseSeasonFolders_areRecognizedAndOrdered() {
        // moto 真机：`动画/【我推的孩子】/1.第一季`、`2.第二季`、`3.第三季`
        assertEquals("1.第一季", SeriesKey.seasonLabelOf("/动画/【我推的孩子】/1.第一季"))
        assertEquals("3.第三季", SeriesKey.seasonLabelOf("/动画/【我推的孩子】/3.第三季"))
        assertEquals("第一季", SeriesKey.seasonLabelOf("/动画/剧/第一季"))
        assertTrue(SeriesKey.seasonNumberOf("1.第一季") < SeriesKey.seasonNumberOf("2.第二季"))
        assertTrue(SeriesKey.seasonNumberOf("2.第二季") < SeriesKey.seasonNumberOf("3.第三季"))
        assertEquals(11, SeriesKey.seasonNumberOf("第11季"))
        assertEquals(11, SeriesKey.seasonNumberOf("第十一季"))
    }

    @Test
    fun embeddedSeasonInFolderName_isRecognized() {
        // moto 真机：`Movies/我推的孩子S03`，剧名与季号写在同一段目录名里
        assertEquals("S03", SeriesKey.seasonLabelOf("/storage/7D73-3621/Movies/我推的孩子S03"))
        assertEquals(
            "我推的孩子",
            SeriesKey.seriesTitleOf("/storage/7D73-3621/Movies/我推的孩子S03")
        )
        val s01 = SeriesKey.seriesKeyOf(
            "/storage/7D73-3621/Movies/我推的孩子S01", MediaLibType.LOCAL, null
        )
        val s03 = SeriesKey.seriesKeyOf(
            "/storage/7D73-3621/Movies/我推的孩子S03", MediaLibType.LOCAL, null
        )
        val other = SeriesKey.seriesKeyOf(
            "/storage/7D73-3621/Movies/剑来S02", MediaLibType.LOCAL, null
        )
        assertTrue(s01.isNotBlank())
        assertEquals(s01, s03)
        assertNotEquals(s01, other)
    }

    @Test
    fun plainFolderInSameParent_isNotGrouped() {
        assertTrue(
            SeriesKey.seriesKeyOf("/storage/emulated/0/Movies/成片", MediaLibType.LOCAL, null)
                .isEmpty()
        )
    }

    @Test
    fun libraryMetadata_usesDecodedTitleAndSeriesTitle() {
        val media = MediaData(
            title = "S01&#58; 第一季",
            path = "/storage/emulated/0/剧集/S01",
            type = MediaLibType.LOCAL
        )
        media.refreshLibraryMetadata()
        assertEquals(1, media.seasonNumber)
        assertEquals("S01: 第一季 剧集", media.searchTitle)
    }

    @Test
    fun libraryMetadata_preservesStoredKeyOnlyWhenPathHasNoSeason() {
        val media = MediaData(
            title = "自定义剧集",
            path = "/storage/emulated/0/Movies/成片",
            type = MediaLibType.LOCAL,
            seriesKey = "stored-key"
        )
        media.refreshLibraryMetadata(preserveExistingKey = true)
        assertEquals("stored-key", media.seriesKey)
        media.refreshLibraryMetadata()
        assertEquals("", media.seriesKey)
    }
}
