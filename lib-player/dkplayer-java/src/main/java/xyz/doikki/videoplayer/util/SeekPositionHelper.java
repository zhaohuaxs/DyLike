package xyz.doikki.videoplayer.util;

/**
 * Seek 位置辅助计算(纯逻辑,可单元测试)。
 * 解决拖动进度条后进度回退问题:seek 期间用 pendingPosition 显示,
 * player 实际 position 追上后清除 pending。
 */
public final class SeekPositionHelper {

    /** 允许的 position 误差(ms),小于此差值视为"已追上"。 */
    public static final long CAUGHT_UP_THRESHOLD_MS = 1000;

    private SeekPositionHelper() {}

    /**
     * 计算应显示的 position。
     *
     * @param actualPosition player 实际位置(ms)
     * @param pendingPosition seek 目标位置(ms),-1 表示无 pending
     * @return 应显示的位置
     */
    public static long computeDisplayPosition(long actualPosition, long pendingPosition) {
        if (pendingPosition < 0) {
            return actualPosition;
        }
        if (Math.abs(actualPosition - pendingPosition) > CAUGHT_UP_THRESHOLD_MS) {
            return pendingPosition;
        }
        // player 已追上,用实际值
        return actualPosition;
    }

    /**
     * 判断 seek 是否已完成(player 追上目标)。
     *
     * @param actualPosition player 实际位置(ms)
     * @param pendingPosition seek 目标位置(ms),-1 表示无 pending
     * @return true 如果 seek 已完成或没有 pending
     */
    public static boolean isSeekComplete(long actualPosition, long pendingPosition) {
        if (pendingPosition < 0) return true;
        return Math.abs(actualPosition - pendingPosition) <= CAUGHT_UP_THRESHOLD_MS;
    }

    /**
     * 计算相对 seek 的目标位置(夹紧到 [0, duration])。
     *
     * @param currentPosition 当前位置(ms)
     * @param deltaMs 偏移量(ms,正=前进,负=后退)
     * @param duration 总时长(ms),<=0 表示未知(不夹紧上界)
     * @return 目标位置
     */
    public static long computeSeekTarget(long currentPosition, long deltaMs, long duration) {
        long target = currentPosition + deltaMs;
        if (target < 0) target = 0;
        if (duration > 0 && target > duration) target = duration;
        return target;
    }
}
