package xyz.doikki.videoplayer.exo;

import androidx.media3.common.Player;

/**
 * 播放状态处理逻辑(纯逻辑,可单元测试)。
 * 从 ExoMediaPlayer.onPlaybackStateChanged 提取。
 *
 * 防止回归:
 * - prepare 阶段收到 STATE_ENDED 时不触发 onCompletion 导致进度不清零
 */
public final class PlaybackStateResolver {

    public static final int STATE_BUFFERING = Player.STATE_BUFFERING;
    public static final int STATE_READY = Player.STATE_READY;
    public static final int STATE_ENDED = Player.STATE_ENDED;
    public static final int STATE_IDLE = Player.STATE_IDLE;

    /** 状态回调应该执行的动作。 */
    public enum Action {
        NONE,
        CALL_PREPARED,
        CALL_COMPLETION,
        CALL_BUFFERING_START,
        CALL_BUFFERING_END
    }

    private PlaybackStateResolver() {}

    /**
     * 计算收到播放状态变化后应执行的动作。
     *
     * @param playbackState ExoPlayer 的播放状态
     * @param isPreparing 是否处于 prepare 阶段
     * @return 应执行的动作
     */
    public static Action resolve(int playbackState, boolean isPreparing) {
        if (isPreparing) {
            if (playbackState == STATE_READY) {
                return Action.CALL_PREPARED;
            } else if (playbackState == STATE_ENDED) {
                // prepare 阶段就 ENDED(进度跳到末尾导致),也要触发 onCompletion。
                return Action.CALL_COMPLETION;
            }
            return Action.NONE;
        }
        switch (playbackState) {
            case STATE_BUFFERING:
                return Action.CALL_BUFFERING_START;
            case STATE_READY:
                return Action.CALL_BUFFERING_END;
            case STATE_ENDED:
                return Action.CALL_COMPLETION;
            default:
                return Action.NONE;
        }
    }
}
