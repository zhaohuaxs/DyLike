package xyz.doikki.videoplayer.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 播放器相关工具类
 * Created by Doikki on 2017/4/10.
 */

public final class PlayerUtils {

    private PlayerUtils() {
    }

    /**
     * 获取状态栏高度
     */
    public static double getStatusBarHeight(Context context) {
        int statusBarHeight = 0;
        //获取status_bar_height资源的ID
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            //根据资源ID获取响应的尺寸值
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);
        }
        return statusBarHeight;
    }

    /**
     * 获取竖屏下状态栏高度
     */
    public static double getStatusBarHeightPortrait(Context context) {
        int statusBarHeight = 0;
        //获取status_bar_height_portrait资源的ID
        int resourceId = context.getResources().getIdentifier("status_bar_height_portrait", "dimen", "android");
        if (resourceId > 0) {
            //根据资源ID获取响应的尺寸值
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);
        }
        return statusBarHeight;
    }

    /**
     * 获取NavigationBar的高度
     */
    public static int getNavigationBarHeight(Context context) {
        if (!hasNavigationBar(context)) {
            return 0;
        }
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height",
                "dimen", "android");
        //获取NavigationBar的高度
        return resources.getDimensionPixelSize(resourceId);
    }

    /**
     * 是否存在NavigationBar
     */
    public static boolean hasNavigationBar(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Display display = getWindowManager(context).getDefaultDisplay();
            Point size = new Point();
            Point realSize = new Point();
            display.getSize(size);
            display.getRealSize(realSize);
            return realSize.x != size.x || realSize.y != size.y;
        } else {
            boolean menu = ViewConfiguration.get(context).hasPermanentMenuKey();
            boolean back = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK);
            return !(menu || back);
        }
    }

    /**
     * 获取屏幕宽度
     */
    public static int getScreenWidth(Context context, boolean isIncludeNav) {
        if (isIncludeNav) {
            //return context.getResources().getDisplayMetrics().widthPixels + getNavigationBarHeight(context);
            return Resources.getSystem().getDisplayMetrics().widthPixels + getNavigationBarHeight(context);
        } else {
            //return context.getResources().getDisplayMetrics().widthPixels;
            return Resources.getSystem().getDisplayMetrics().widthPixels;
        }
    }

    /**
     * 获取屏幕高度
     */
    public static int getScreenHeight(Context context, boolean isIncludeNav) {
        if (isIncludeNav) {
            //return context.getResources().getDisplayMetrics().heightPixels + getNavigationBarHeight(context);
            return Resources.getSystem().getDisplayMetrics().heightPixels + getNavigationBarHeight(context);
        } else {
            //return context.getResources().getDisplayMetrics().heightPixels;
            return Resources.getSystem().getDisplayMetrics().heightPixels;
        }
    }

    /**
     * 获取Activity
     */
    public static Activity scanForActivity(Context context) {
        if (context == null) return null;
        if (context instanceof Activity) {
            return (Activity) context;
        } else if (context instanceof ContextWrapper) {
            return scanForActivity(((ContextWrapper) context).getBaseContext());
        }
        return null;
    }

    /**
     * dp转为px
     */
    public static int dp2px(Context context, float dpValue) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpValue, context.getResources().getDisplayMetrics());
    }

    /**
     * sp转为px
     */
    public static int sp2px(Context context, float dpValue) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, dpValue, context.getResources().getDisplayMetrics());
    }

    /**
     * 如果WindowManager还未创建，则创建一个新的WindowManager返回。否则返回当前已创建的WindowManager。
     */
    public static WindowManager getWindowManager(Context context) {
        return (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * 边缘检测
     * 横屏全屏下需避开：系统返回手势区（约16dp）、刘海物理区域
     * 修复机型兼容问题：原 40dp 阈值过大且 getScreenWidth(true) 横屏下重复计算导航栏宽度，
     * 导致左右两侧亮度/音量调节区被误判为边缘而无法响应。
     */
    public static boolean isEdge(Context context, MotionEvent e) {
        // 系统返回手势区阈值（Android 10+ 约 16-24dp），保留 16dp 避让
        int edgeSize = dp2px(context, 16);
        float rawX = e.getRawX();
        float rawY = e.getRawY();

        // 使用真实物理屏幕尺寸，不再 + 导航栏高度
        // （沉浸式下 widthPixels/heightPixels 已是全屏宽度，原 +navBarHeight 会重复计算）
        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int screenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;

        // 基本四边边缘检测
        if (rawX < edgeSize || rawX > screenWidth - edgeSize
                || rawY < edgeSize || rawY > screenHeight - edgeSize) {
            return true;
        }

        // 横屏下避让刘海区域（刘海在左右两侧，触摸可能被系统消费）
        int cutoutEdge = getCutoutEdgeSize(context);
        if (cutoutEdge > 0) {
            if (rawX < cutoutEdge || rawX > screenWidth - cutoutEdge) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取横屏下刘海的边缘避让宽度（左右两侧）
     * 仅在横屏且有刘海时返回非零值
     */
    private static int getCutoutEdgeSize(Context context) {
        // 仅横屏需要避让左右刘海
        if (Resources.getSystem().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE) {
            return 0;
        }
        Activity activity = scanForActivity(context);
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return 0;
        }
        WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        if (insets == null) return 0;
        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null) return 0;
        // 刘海安全区域 inset，取左右最大值作为避让宽度
        int left = cutout.getSafeInsetLeft();
        int right = cutout.getSafeInsetRight();
        return Math.max(left, right);
    }


    public static final int NO_NETWORK = 0;
    public static final int NETWORK_CLOSED = 1;
    public static final int NETWORK_ETHERNET = 2;
    public static final int NETWORK_WIFI = 3;
    public static final int NETWORK_MOBILE = 4;
    public static final int NETWORK_UNKNOWN = -1;

    /**
     * 判断当前网络类型
     */
    public static int getNetworkType(Context context) {
        //改为context.getApplicationContext()，防止在Android 6.0上发生内存泄漏
        ConnectivityManager connectMgr = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectMgr == null) {
            return NO_NETWORK;
        }

        NetworkInfo networkInfo = connectMgr.getActiveNetworkInfo();
        if (networkInfo == null) {
            // 没有任何网络
            return NO_NETWORK;
        }
        if (!networkInfo.isConnected()) {
            // 网络断开或关闭
            return NETWORK_CLOSED;
        }
        if (networkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
            // 以太网网络
            return NETWORK_ETHERNET;
        } else if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
            // wifi网络，当激活时，默认情况下，所有的数据流量将使用此连接
            return NETWORK_WIFI;
        } else if (networkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
            // 移动数据连接,不能与连接共存,如果wifi打开，则自动关闭
            switch (networkInfo.getSubtype()) {
                // 2G
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    // 3G
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                    // 4G
                case TelephonyManager.NETWORK_TYPE_LTE:
                    // 5G
                case TelephonyManager.NETWORK_TYPE_NR:
                    return NETWORK_MOBILE;
            }
        }
        // 未知网络
        return NETWORK_UNKNOWN;
    }

    /**
     * 通过反射获取Application
     *
     * @deprecated 不在使用，后期谷歌可能封掉该接口
     */
    @SuppressLint("PrivateApi")
    @Deprecated
    public static Application getApplication() {
        try {
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取当前系统时间
     */
    public static String getCurrentSystemTime() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        Date date = new Date();
        return simpleDateFormat.format(date);
    }

    /**
     * 格式化时间
     */
    public static String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;

        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours = totalSeconds / 3600;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        }
    }

    public static String formatNetworkSpeed(long bytesPerSecond) {
        long speed = Math.max(0L, bytesPerSecond);
        if (speed >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB/s", speed / (1024.0 * 1024.0));
        }
        if (speed >= 1024L) {
            return String.format(Locale.US, "%d KB/s", speed / 1024L);
        }
        return speed + " B/s";
    }

    /**
     * 获取集合的快照
     */
    @NonNull
    public static <T> List<T> getSnapshot(@NonNull Collection<T> other) {
        List<T> result = new ArrayList<>(other.size());
        for (T item : other) {
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }


    /**
     * 获取屏幕宽度（支持高版本，处理横竖屏和导航栏）
     *
     * @param context    上下文
     * @param isIncludeNav 是否包含导航栏宽度（仅在导航栏在侧边时生效，横屏可能在左右，竖屏在底部）
     * @return 屏幕宽度（像素）
     */
    public static int getScreenWidth2(Context context, boolean isIncludeNav) {
        if (context == null) {
            return 0;
        }

        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return 0;
        }

        Display display = windowManager.getDefaultDisplay();
        Point outSize = new Point();

        // 高版本（API 30+）使用 getRealSize 或 getRealMetrics 获取物理屏幕尺寸
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 获取物理屏幕尺寸（包含所有系统装饰，如导航栏）
            display.getRealSize(outSize);
        } else {
            // 低版本兼容
            display.getRealSize(outSize); // 替代 getRealMetrics，直接获取宽高
        }

        // 判断当前屏幕方向（横竖屏）
        //int orientation = context.getResources().getConfiguration().orientation;
        int orientation = Resources.getSystem().getConfiguration().orientation;
        int physicalWidth, physicalHeight;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏：宽度是较大的值，高度是较小的值
            physicalWidth = Math.max(outSize.x, outSize.y);
            physicalHeight = Math.min(outSize.x, outSize.y);
        } else {
            // 竖屏：宽度是较小的值，高度是较大的值
            physicalWidth = Math.min(outSize.x, outSize.y);
            physicalHeight = Math.max(outSize.x, outSize.y);
        }
        Log.d("TAG", "getScreenWidth2: " + orientation + " " + physicalWidth + " " + physicalHeight + " " + display.getRotation() );

        // 如果不包含导航栏，需要减去导航栏的宽度（仅当导航栏在宽度方向时）
        if (!isIncludeNav) {
            int navBarWidth = getNavigationBarWidthInOrientation(context, orientation);
            physicalWidth -= navBarWidth;
        }

        return physicalWidth;
    }

    /**
     * 根据屏幕方向获取导航栏的宽度（横屏时导航栏可能在左右，占宽度；竖屏时在底部，占高度）
     */
    private static int getNavigationBarWidthInOrientation(Context context, int orientation) {
        int navBarSize = getNavigationBarSize(context, orientation);
        // 横屏时导航栏宽度为实际尺寸，竖屏时导航栏宽度为0（因为占高度）
        return orientation == Configuration.ORIENTATION_LANDSCAPE ? navBarSize : 0;
    }

    /**
     * 获取导航栏的尺寸（根据方向返回宽度或高度）
     */
    private static int getNavigationBarSize(Context context, int orientation) {
        if (context == null) {
            return 0;
        }

        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier(
                orientation == Configuration.ORIENTATION_LANDSCAPE
                        ? "navigation_bar_width"
                        : "navigation_bar_height",
                "dimen",
                "android"
        );

        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        }
        return 0;
    }

}
