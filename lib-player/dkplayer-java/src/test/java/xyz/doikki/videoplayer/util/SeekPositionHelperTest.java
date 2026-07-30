package xyz.doikki.videoplayer.util;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * SeekPositionHelper 单元测试。
 *
 * 防止回归:
 * - 拖动进度条后进度条回退(应该停在目标位置直到 player 追上)
 * - 后退/前进按钮计算错误(越界)
 */
public class SeekPositionHelperTest {

    // === computeDisplayPosition ===

    @Test
    public void displayPosition_noPending_returnsActual() {
        assertEquals(50000, SeekPositionHelper.computeDisplayPosition(50000, -1));
    }

    @Test
    public void displayPosition_pendingFarAhead_returnsPending() {
        // player 在 28秒,seek 目标 50秒,差 22秒 > 1秒阈值
        assertEquals(50000, SeekPositionHelper.computeDisplayPosition(28000, 50000));
    }

    @Test
    public void displayPosition_pendingClose_returnsActual() {
        // player 在 49.5秒,seek 目标 50秒,差 0.5秒 < 1秒阈值
        assertEquals(49500, SeekPositionHelper.computeDisplayPosition(49500, 50000));
    }

    @Test
    public void displayPosition_pendingBehind_returnsPending() {
        // 后退:player 在 50秒,seek 目标 20秒,差 30秒 > 1秒阈值
        assertEquals(20000, SeekPositionHelper.computeDisplayPosition(50000, 20000));
    }

    // === isSeekComplete ===

    @Test
    public void isSeekComplete_noPending_returnsTrue() {
        assertTrue(SeekPositionHelper.isSeekComplete(50000, -1));
    }

    @Test
    public void isSeekComplete_farFromTarget_returnsFalse() {
        assertFalse(SeekPositionHelper.isSeekComplete(28000, 50000));
    }

    @Test
    public void isSeekComplete_atTarget_returnsTrue() {
        assertTrue(SeekPositionHelper.isSeekComplete(50000, 50000));
    }

    @Test
    public void isSeekComplete_withinThreshold_returnsTrue() {
        assertTrue(SeekPositionHelper.isSeekComplete(49600, 50000));
    }

    // === computeSeekTarget ===

    @Test
    public void seekTarget_forward_normal() {
        // 前进15秒:30秒 → 45秒
        assertEquals(45000, SeekPositionHelper.computeSeekTarget(30000, 15000, 94000));
    }

    @Test
    public void seekTarget_rewind_normal() {
        // 后退15秒:30秒 → 15秒
        assertEquals(15000, SeekPositionHelper.computeSeekTarget(30000, -15000, 94000));
    }

    @Test
    public void seekTarget_rewind_pastStart_clampsToZero() {
        // 后退超过开头:5秒 - 15秒 = -10秒 → 夹紧到0
        assertEquals(0, SeekPositionHelper.computeSeekTarget(5000, -15000, 94000));
    }

    @Test
    public void seekTarget_forward_pastEnd_clampsToDuration() {
        // 前进超过末尾:90秒 + 15秒 = 105秒 → 夹紧到94秒
        assertEquals(94000, SeekPositionHelper.computeSeekTarget(90000, 15000, 94000));
    }

    @Test
    public void seekTarget_unknownDuration_noClamp() {
        // 时长未知:不夹紧上界
        assertEquals(105000, SeekPositionHelper.computeSeekTarget(90000, 15000, 0));
    }

    @Test
    public void seekTarget_negativeDuration_noClamp() {
        assertEquals(105000, SeekPositionHelper.computeSeekTarget(90000, 15000, -1));
    }
}
