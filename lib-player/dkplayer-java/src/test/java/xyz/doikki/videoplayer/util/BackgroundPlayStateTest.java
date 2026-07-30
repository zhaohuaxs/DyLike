package xyz.doikki.videoplayer.util;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * BackgroundPlayState 单元测试。
 *
 * 防止回归:
 * - 后台播完切下一集双 player
 * - PiP 关闭后后台播放不触发
 * - 开关关闭时仍进入后台播放
 */
public class BackgroundPlayStateTest {

    // === shouldReleaseBeforeSwitch ===

    @Test
    public void release_holdingAndBackground_returnsTrue() {
        assertTrue(BackgroundPlayState.shouldReleaseBeforeSwitch(true, true));
    }

    @Test
    public void release_notHolding_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldReleaseBeforeSwitch(false, true));
    }

    @Test
    public void release_notBackground_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldReleaseBeforeSwitch(true, false));
    }

    @Test
    public void release_neither_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldReleaseBeforeSwitch(false, false));
    }

    // === shouldEnterBackgroundAfterPip ===

    @Test
    public void afterPip_enabledAndPlaying_returnsTrue() {
        assertTrue(BackgroundPlayState.shouldEnterBackgroundAfterPip(true, true, true));
    }

    @Test
    public void afterPip_disabled_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldEnterBackgroundAfterPip(false, true, true));
    }

    @Test
    public void afterPip_notPlaying_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldEnterBackgroundAfterPip(true, false, true));
    }

    @Test
    public void afterPip_noPlayer_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldEnterBackgroundAfterPip(true, true, false));
    }

    // === shouldPauseAfterPip ===

    @Test
    public void pauseAfterPip_switchOff_returnsTrue() {
        assertTrue(BackgroundPlayState.shouldPauseAfterPip(false));
    }

    @Test
    public void pauseAfterPip_switchOn_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldPauseAfterPip(true));
    }

    // === shouldEnterBackgroundPlay (onStop) ===

    @Test
    public void onStop_enabledPlaying_hasPlayer_notPip_returnsTrue() {
        assertTrue(BackgroundPlayState.shouldEnterBackgroundPlay(true, true, true, false));
    }

    @Test
    public void onStop_disabled_returnsFalse() {
        // 开关关闭时不进后台播放
        assertFalse(BackgroundPlayState.shouldEnterBackgroundPlay(false, true, true, false));
    }

    @Test
    public void onStop_inPip_returnsFalse() {
        // PiP 期间不进后台播放
        assertFalse(BackgroundPlayState.shouldEnterBackgroundPlay(true, true, true, true));
    }

    @Test
    public void onStop_notPlaying_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldEnterBackgroundPlay(true, false, true, false));
    }

    @Test
    public void onStop_noPlayer_returnsFalse() {
        assertFalse(BackgroundPlayState.shouldEnterBackgroundPlay(true, true, false, false));
    }
}
