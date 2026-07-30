package xyz.doikki.videoplayer.util;

/**
 * 播放进度工具方法(纯逻辑,可单元测试)。
 * 从 BaseVideoView 提取,便于测试进度记忆的边界条件。
 */
public final class ProgressUtils {

    /** 接近末尾的阈值(ms),小于此值视为"播完了",进度清零。 */
    public static final long NEAR_END_THRESHOLD_MS = 10000;

    private ProgressUtils() {}

    /**
     * 判断当前进度是否接近末尾(剩余时间 < 10 秒)。
     *
     * @param currentPosition 当前播放位置(ms)
     * @param duration 视频总时长(ms),<=0 视为未知
     * @return true 如果接近末尾,应从头播放
     */
    public static boolean isNearEnd(long currentPosition, long duration) {
        if (duration <= 0) return false;
        if (currentPosition <= 0) return false;
        return duration - currentPosition < NEAR_END_THRESHOLD_MS;
    }

    /**
     * 计算应保存的进度:接近末尾返回 0,否则返回原位置。
     *
     * @param currentPosition 当前播放位置(ms)
     * @param duration 视频总时长(ms)
     * @return 应保存的进度值
     */
    public static long computeSavedProgress(long currentPosition, long duration) {
        if (isNearEnd(currentPosition, duration)) {
            return 0;
        }
        return currentPosition;
    }
}
