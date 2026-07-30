package xyz.doikki.videoplayer.util;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ProgressUtils 单元测试 — 覆盖进度记忆的边界条件。
 *
 * 防止回归之前出现的 bug:
 * - 视频播完后从头播放(而不是从末尾几秒开始)
 * - 循环连播时不会 seek 到旧进度
 */
public class ProgressUtilsTest {

    @Test
    public void isNearEnd_remainingLessThan10s_returnsTrue() {
        // 94秒视频,播到91秒(剩3秒)→ 接近末尾
        assertTrue(ProgressUtils.isNearEnd(91000, 94000));
    }

    @Test
    public void isNearEnd_remainingMoreThan10s_returnsFalse() {
        // 94秒视频,播到80秒(剩14秒)→ 不接近末尾
        assertFalse(ProgressUtils.isNearEnd(80000, 94000));
    }

    @Test
    public void isNearEnd_exactly10s_returnsFalse() {
        // 剩余刚好10秒 → 不算接近(边界 < 10秒)
        assertFalse(ProgressUtils.isNearEnd(84000, 94000));
    }

    @Test
    public void isNearEnd_positionZero_returnsFalse() {
        // 进度为0(刚开头)→ 不接近末尾
        assertFalse(ProgressUtils.isNearEnd(0, 94000));
    }

    @Test
    public void isNearEnd_durationZero_returnsFalse() {
        // 时长未知 → 不接近末尾
        assertFalse(ProgressUtils.isNearEnd(50000, 0));
    }

    @Test
    public void isNearEnd_durationNegative_returnsFalse() {
        assertFalse(ProgressUtils.isNearEnd(50000, -1));
    }

    @Test
    public void computeSavedProgress_nearEnd_returnsZero() {
        // 接近末尾 → 保存0(下次从头播放)
        assertEquals(0, ProgressUtils.computeSavedProgress(93000, 94000));
    }

    @Test
    public void computeSavedProgress_notNearEnd_returnsPosition() {
        // 不接近末尾 → 保存当前进度
        assertEquals(50000, ProgressUtils.computeSavedProgress(50000, 94000));
    }

    @Test
    public void computeSavedProgress_positionZero_returnsZero() {
        assertEquals(0, ProgressUtils.computeSavedProgress(0, 94000));
    }

    @Test
    public void computeSavedProgress_durationUnknown_returnsPosition() {
        // 时长未知 → 正常保存进度
        assertEquals(50000, ProgressUtils.computeSavedProgress(50000, 0));
    }

    // === 循环连播场景 ===

    @Test
    public void computeSavedProgress_shortVideo_nearEnd() {
        // 短视频(15秒),播到14秒 → 接近末尾
        assertEquals(0, ProgressUtils.computeSavedProgress(14000, 15000));
    }

    @Test
    public void computeSavedProgress_longVideo_notNearEnd() {
        // 长视频(1小时),播到30分钟 → 正常保存
        assertEquals(1800000, ProgressUtils.computeSavedProgress(1800000, 3600000));
    }

    @Test
    public void computeSavedProgress_loopReplay_fromEnd() {
        // 模拟循环重播:上一轮播完(进度=duration),应该从头播放
        assertEquals(0, ProgressUtils.computeSavedProgress(94400, 94400));
    }
}
