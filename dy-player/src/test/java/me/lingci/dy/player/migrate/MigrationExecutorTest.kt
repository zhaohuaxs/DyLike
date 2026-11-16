package me.lingci.dy.player.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移写入 play_history 的时间戳不变量。
 *
 * 背景：首页「继续观看」与历史页都用 `updated_at` 定位「每组最新一条」。
 * 早期实现把同一批历史统一写成 `now`，同一媒体库的多集因此并列 MAX，
 * 续播查询一次返回同一 mediaId 的多行，Lazy 列表 key 重复直接崩
 * （`Key "..." was already used`）。
 */
class MigrationExecutorTest {

    @Test
    fun playedAt_isStrictlyIncreasing_andUnique() {
        val now = 1_700_000_000_000L
        for (total in listOf(1, 2, 10, 100)) {
            val stamps = (0 until total).map { migratedPlayedAt(now, total, it) }
            assertEquals("时间戳必须逐行唯一", total, stamps.distinct().size)
            assertTrue("必须随列表序递增（越靠后越新）", stamps == stamps.sorted())
            assertEquals("最后一条（最新）应等于迁移时刻", now, stamps.last())
        }
    }

    @Test
    fun playedAt_keepsOneSecondGapBetweenAdjacentRows() {
        val now = 1_700_000_000_000L
        val total = 3
        val stamps = (0 until total).map { migratedPlayedAt(now, total, it) }
        assertEquals(1_000L, stamps[1] - stamps[0])
        assertEquals(1_000L, stamps[2] - stamps[1])
    }

    @Test
    fun playedAt_emptyBatch_isNotCalled_butSingleRowEqualsNow() {
        assertEquals(1_700_000_000_000L, migratedPlayedAt(1_700_000_000_000L, 1, 0))
    }
}
