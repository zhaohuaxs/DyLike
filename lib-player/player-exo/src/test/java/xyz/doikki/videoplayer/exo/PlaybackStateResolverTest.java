package xyz.doikki.videoplayer.exo;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * PlaybackStateResolver 单元测试。
 *
 * 防止回归之前出现的 bug:
 * - prepare 阶段收到 STATE_ENDED 时不触发 onCompletion
 *   导致进度不清零、循环重播从末尾开始
 */
public class PlaybackStateResolverTest {

    // === prepare 阶段 ===

    @Test
    public void prepare_ready_callsPrepared() {
        assertEquals(PlaybackStateResolver.Action.CALL_PREPARED,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_READY, true));
    }

    @Test
    public void prepare_ended_callsCompletion() {
        // 这个测试覆盖之前的核心 bug:prepare 阶段 ENDED 不触发 onCompletion
        assertEquals(PlaybackStateResolver.Action.CALL_COMPLETION,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_ENDED, true));
    }

    @Test
    public void prepare_buffering_doesNothing() {
        assertEquals(PlaybackStateResolver.Action.NONE,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_BUFFERING, true));
    }

    @Test
    public void prepare_idle_doesNothing() {
        assertEquals(PlaybackStateResolver.Action.NONE,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_IDLE, true));
    }

    // === 非 prepare 阶段 ===

    @Test
    public void notPrepare_ended_callsCompletion() {
        assertEquals(PlaybackStateResolver.Action.CALL_COMPLETION,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_ENDED, false));
    }

    @Test
    public void notPrepare_buffering_callsBufferingStart() {
        assertEquals(PlaybackStateResolver.Action.CALL_BUFFERING_START,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_BUFFERING, false));
    }

    @Test
    public void notPrepare_ready_callsBufferingEnd() {
        assertEquals(PlaybackStateResolver.Action.CALL_BUFFERING_END,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_READY, false));
    }

    @Test
    public void notPrepare_idle_doesNothing() {
        assertEquals(PlaybackStateResolver.Action.NONE,
            PlaybackStateResolver.resolve(PlaybackStateResolver.STATE_IDLE, false));
    }
}
