package xyz.doikki.videoplayer.util;

/**
 * 通知 PendingIntent requestCode 计算(纯逻辑,可单元测试)。
 *
 * 防止回归:
 * - 不同 Activity 产生不同 requestCode,避免 PendingIntent 缓存冲突
 *   (之前用固定 requestCode=0 导致点击通知打开错误页面)
 */
public final class NotificationRequestCode {

    private NotificationRequestCode() {}

    /**
     * 根据目标 Activity 类名计算 requestCode。
     * 不同类名产生不同值,同一类名稳定一致。
     *
     * @param className Activity 的全限定类名
     * @return 稳定的 requestCode
     */
    public static int fromClassName(String className) {
        if (className == null || className.isEmpty()) {
            return 0;
        }
        return className.hashCode();
    }

    /**
     * 判断两个类名是否会产生不同的 requestCode。
     *
     * @param className1 第一个类名
     * @param className2 第二个类名
     * @return true 如果 requestCode 不同
     */
    public static boolean producesDifferentCode(String className1, String className2) {
        return fromClassName(className1) != fromClassName(className2);
    }
}
