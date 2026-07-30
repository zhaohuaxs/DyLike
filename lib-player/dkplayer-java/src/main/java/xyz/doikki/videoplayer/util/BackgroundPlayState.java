package xyz.doikki.videoplayer.util;

/**
 * 后台播放状态判断(纯逻辑,可单元测试)。
 *
 * 防止回归:
 * - 后台播完切下一集时双 player(旧 player 在 Service,新 player 在 Activity)
 *   原因: STATE_PLAYBACK_COMPLETED 时没有先释放 Service 的旧 player
 *   修复: 判断 isHoldingPlayer && isBackgroundMode → 需要 releaseOldPlayer
 * - PiP 关闭后后台播放不触发
 *   原因: PiP 退出后 Activity 回前台,不触发 onStop
 *   修复: 根据 backgroundPlayEnabled 决定 PiP 退出后行为
 */
public final class BackgroundPlayState {

    private BackgroundPlayState() {}

    /**
     * 判断后台播放完成后是否需要先释放旧 player。
     *
     * @param isHoldingPlayer Service 是否持有 player
     * @param isBackgroundMode 是否在后台播放模式
     * @return true 需要 releaseOldPlayer 再切下一集
     */
    public static boolean shouldReleaseBeforeSwitch(boolean isHoldingPlayer, boolean isBackgroundMode) {
        return isHoldingPlayer && isBackgroundMode;
    }

    /**
     * 判断 PiP 退出后是否应该进入后台播放。
     *
     * @param backgroundPlayEnabled 后台播放开关
     * @param isPlaying 视频是否在播放
     * @param hasPlayer 是否有 player
     * @return true 应该进入后台播放
     */
    public static boolean shouldEnterBackgroundAfterPip(boolean backgroundPlayEnabled, boolean isPlaying, boolean hasPlayer) {
        return backgroundPlayEnabled && isPlaying && hasPlayer;
    }

    /**
     * 判断 PiP 退出后是否应该暂停播放。
     *
     * @param backgroundPlayEnabled 后台播放开关
     * @return true 应该暂停(开关关闭时)
     */
    public static boolean shouldPauseAfterPip(boolean backgroundPlayEnabled) {
        return !backgroundPlayEnabled;
    }

    /**
     * 判断是否应该进入后台播放(onStop 触发)。
     *
     * @param backgroundPlayEnabled 后台播放开关
     * @param isPlaying 视频是否在播放(PLAYING 或 BUFFERED)
     * @param hasPlayer 是否有 player
     * @param isInPictureInPictureMode 是否在 PiP 模式
     * @return true 应该进入后台播放
     */
    public static boolean shouldEnterBackgroundPlay(boolean backgroundPlayEnabled, boolean isPlaying, boolean hasPlayer, boolean isInPictureInPictureMode) {
        if (!backgroundPlayEnabled) return false;
        if (isInPictureInPictureMode) return false;
        return isPlaying && hasPlayer;
    }
}
