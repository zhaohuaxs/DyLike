package xyz.doikki.videoplayer.util;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * NotificationRequestCode 单元测试。
 *
 * 防止回归:
 * - 点击通知打开错误页面(MainActivity 而非 LongVideoActivity)
 *   原因: 固定 requestCode=0 导致 PendingIntent 被系统缓存
 *   修复: 用 cls.name.hashCode() 做 requestCode
 */
public class NotificationRequestCodeTest {

    @Test
    public void differentClassNames_produceDifferentCodes() {
        String longVideo = "me.lingci.dy.player.ui.long_video.LongVideoActivity";
        String shortVideo = "me.lingci.dy.player.ui.short_video.ShortVideoActivity";
        String main = "me.lingci.dy.player.ui.main.MainActivity";

        assertTrue(NotificationRequestCode.producesDifferentCode(longVideo, shortVideo));
        assertTrue(NotificationRequestCode.producesDifferentCode(longVideo, main));
        assertTrue(NotificationRequestCode.producesDifferentCode(shortVideo, main));
    }

    @Test
    public void sameClassName_producesSameCode() {
        String name = "me.lingci.dy.player.ui.long_video.LongVideoActivity";
        assertEquals(NotificationRequestCode.fromClassName(name), NotificationRequestCode.fromClassName(name));
    }

    @Test
    public void nullClassName_returnsZero() {
        assertEquals(0, NotificationRequestCode.fromClassName(null));
    }

    @Test
    public void emptyClassName_returnsZero() {
        assertEquals(0, NotificationRequestCode.fromClassName(""));
    }

    @Test
    public void longVideoActivity_notZero() {
        // 之前用 requestCode=0 导致 PendingIntent 缓存冲突
        // LongVideoActivity 的 hashCode 不能为 0
        assertNotEquals(0, NotificationRequestCode.fromClassName("me.lingci.dy.player.ui.long_video.LongVideoActivity"));
    }

    @Test
    public void shortVideoActivity_notZero() {
        assertNotEquals(0, NotificationRequestCode.fromClassName("me.lingci.dy.player.ui.short_video.ShortVideoActivity"));
    }
}
