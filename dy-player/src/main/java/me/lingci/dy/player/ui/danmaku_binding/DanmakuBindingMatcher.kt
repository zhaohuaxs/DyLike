package me.lingci.dy.player.ui.danmaku_binding

import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.SeriesKey
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.Comparators
import java.text.Normalizer
import java.util.Locale

object DanmakuBindingMatcher {

    private const val CN_NUMBER = "[0-9]+|[零〇一二三四五六七八九十百千万两]+"

    private val tagTokens = setOf(
        "1080p", "2160p", "720p", "480p", "4320p", "4k", "8k", "bd", "bdrip", "bluray", "web", "webrip",
        "webdl", "dl", "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "flac", "dts", "truehd",
        "10bit", "8bit", "12bit", "gb", "big5", "chs", "cht", "sc", "tc", "中字", "简中", "繁中", "双语",
        "国语", "粤语", "日语", "英语", "高清", "修复", "合集"
    )

    private val seasonEpisodePattern = Regex("(?<![a-z0-9])s(\\d{1,2})e(\\d{1,4})(?!\\d)")
    private val episodePattern = Regex("(?<![a-z0-9])(?:episode|ep|e)[\\s._-]*(\\d{1,4})(?!\\d)")
    private val chineseEpisodePattern = Regex("第\\s*($CN_NUMBER)\\s*[集话話期]")
    private val versionEpisodePattern = Regex("(?<![a-z0-9])(\\d{1,4})v\\d{1,2}(?![a-z0-9])")
    private val numberPattern = Regex("(?<![a-z0-9])(\\d{1,4})(?![a-z0-9])")
    private val chineseSeasonPattern = Regex("第\\s*($CN_NUMBER)\\s*季")
    private val seasonPattern = Regex("(?<![a-z0-9])season[\\s._-]*(\\d{1,2})(?!\\d)")
    private val shortSeasonPattern = Regex("(?<![a-z0-9])s(\\d{1,2})(?!\\d)")
    private val yearPattern = Regex("(?<![a-z0-9])(?:19|20)\\d{2}(?![a-z0-9])")
    private val episodeRangePattern = Regex("\\d{1,4}\\s*(?:-|~|～|至|到)\\s*\\d{1,4}")
    private val repeatedEpisodePattern = Regex("(?<![a-z0-9])(?:s\\d{1,2})?e\\d{1,4}\\s*e\\d{1,4}")

    data class Candidate(
        val file: FileEntity,
        val score: Int,
        val detail: String,
        val episodeSame: Boolean? = null,
        val autoBindable: Boolean = false
    )

    fun match(
        videoData: VideoData,
        files: List<FileEntity>,
        videoSeasonHint: Int? = null,
        fileSeasonHint: Int? = null
    ): List<Candidate> {
        if (files.isEmpty()) return emptyList()
        val videoName = videoData.name.ifBlank { videoData.videoUrl.substringAfterLast('/') }
        val videoParsed = parse(videoName)
        val videoSeason = videoParsed.season ?: videoSeasonHint?.takeIf { it >= 0 }
        val videoSeasonConflict = videoParsed.season != null && videoSeasonHint != null &&
            videoParsed.season != videoSeasonHint
        return files.map { file ->
            val fileParsed = parse(file.name.ifBlank { file.title })
            val fileSeason = fileParsed.season ?: fileSeasonHint?.takeIf { it >= 0 }
            val fileSeasonConflict = fileParsed.season != null && fileSeasonHint != null &&
                fileParsed.season != fileSeasonHint
            val episodeSame = if (videoParsed.episode != null && fileParsed.episode != null) {
                videoParsed.episode == fileParsed.episode
            } else {
                null
            }
            val seasonMismatch = videoSeason != null && fileSeason != null && videoSeason != fileSeason
            val seasonContextConflict = videoSeasonConflict || fileSeasonConflict
            val titleScore = titleScore(videoParsed, fileParsed)
            val score = score(episodeSame, seasonMismatch, seasonContextConflict, videoSeason, fileSeason, titleScore)
            val autoBindable = !videoParsed.multipleEpisodes && !fileParsed.multipleEpisodes &&
                episodeSame == true && !seasonMismatch && !seasonContextConflict && titleScore >= 20
            Candidate(
                file = file,
                score = score,
                detail = buildDetail(episodeSame, score),
                episodeSame = episodeSame,
                autoBindable = autoBindable
            )
        }.filter { it.score > 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy(Comparators.stringComparator()) { it.file.name }
            )
    }

    fun shouldAutoBind(candidates: List<Candidate>): Boolean {
        val first = candidates.firstOrNull() ?: return false
        if (!first.autoBindable || first.score < 70) return false
        val second = candidates.getOrNull(1)
        return second == null || first.score - second.score >= 12
    }

    private fun score(
        episodeSame: Boolean?,
        seasonMismatch: Boolean,
        seasonContextConflict: Boolean,
        videoSeason: Int?,
        fileSeason: Int?,
        titleScore: Int
    ): Int {
        var score = titleScore
        if (episodeSame != null) {
            score += if (episodeSame) 50 else -40
        }
        when {
            seasonMismatch || seasonContextConflict -> score -= 30
            videoSeason != null && fileSeason != null -> score += 10
        }
        return score.coerceAtLeast(0)
    }

    private fun titleScore(videoParsed: ParsedName, fileParsed: ParsedName): Int {
        if (videoParsed.normalized.isBlank() || fileParsed.normalized.isBlank()) return 0
        return when {
            videoParsed.normalized == fileParsed.normalized -> 35
            else -> overlapScore(videoParsed.tokens, fileParsed.tokens)
        }
    }

    private fun overlapScore(a: Set<String>, b: Set<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val common = a.intersect(b)
        if (common.isEmpty()) return 0
        return maxOf(1, (common.size * 20) / maxOf(a.size, b.size))
    }

    private fun parse(name: String): ParsedName {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val base = normalized.substringAfterLast('/').substringBeforeLast('.')
        val multipleEpisodes = hasMultipleEpisodes(base)
        val compact = base
            .replace(Regex("[【】\\[\\](){}]"), " ")
            .replace(Regex("[_./\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val episode = extractEpisode(compact)
        val season = extractSeason(compact)
        val titleText = compact
            .replace(chineseEpisodePattern, " ")
            .replace(chineseSeasonPattern, " ")
            .replace(seasonEpisodePattern, " ")
            .replace(episodePattern, " ")
            .replace(versionEpisodePattern, " ")
            .replace(seasonPattern, " ")
            .replace(shortSeasonPattern, " ")
            .replace(yearPattern, " ")
        val tokens = titleText.split(Regex("[\\s_./\\\\:-]+"))
            .map { it.trim() }
            .filter { token ->
                token.isNotBlank() &&
                    (token.length > 1 || token.first().code > 127) &&
                    token !in tagTokens &&
                    !token.matches(Regex("\\d+")) &&
                    !token.matches(Regex("v\\d+"))
            }
            .toSet()
        return ParsedName(
            normalized = tokens.joinToString(" "),
            episode = episode,
            season = season,
            tokens = tokens,
            multipleEpisodes = multipleEpisodes
        )
    }

    private fun extractEpisode(text: String): Int? {
        seasonEpisodePattern.find(text)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { return it }
        chineseEpisodePattern.find(text)?.groupValues?.getOrNull(1)?.let { parseNumber(it) }?.let { return it }
        episodePattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        versionEpisodePattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val numbers = numberPattern.findAll(text).map { it.value }.toList()
        return numbers.singleOrNull()
            ?.takeIf { it.length <= 3 }
            ?.toIntOrNull()
    }

    private fun extractSeason(text: String): Int? {
        chineseSeasonPattern.find(text)?.groupValues?.getOrNull(1)?.let { parseNumber(it) }?.let { return it }
        seasonPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        seasonEpisodePattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return shortSeasonPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasMultipleEpisodes(text: String): Boolean {
        if (episodeRangePattern.containsMatchIn(text) || repeatedEpisodePattern.containsMatchIn(text)) return true
        val markerCount = seasonEpisodePattern.findAll(text).count() +
            episodePattern.findAll(text).count() +
            chineseEpisodePattern.findAll(text).count()
        return markerCount > 1
    }

    private fun parseNumber(token: String): Int? {
        val value = SeriesKey.seasonNumberOf("第${token.replace('两', '二')}季")
        return value.takeIf { it != Int.MAX_VALUE }
    }

    private fun buildDetail(episodeSame: Boolean?, score: Int): String {
        val episode = when (episodeSame) {
            true -> "集数一致"
            false -> "集数不同"
            null -> ""
        }
        return listOf(episode, "匹配度$score").filter { it.isNotBlank() }.joinToString(" ")
    }

    private data class ParsedName(
        val normalized: String,
        val episode: Int?,
        val season: Int?,
        val tokens: Set<String>,
        val multipleEpisodes: Boolean
    )
}
