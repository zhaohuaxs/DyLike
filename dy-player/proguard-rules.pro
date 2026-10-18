# ============================================================
# dy-player ProGuard Rules for AGP 9 & R8
# ============================================================

# ============================================================
# 1. Room Database
# ============================================================
# 保留所有 Room 实体类
-keep @androidx.room3.Entity class * { *; }

# 保留所有 DAO 接口
-keep @androidx.room3.Dao interface * { *; }
-keep @androidx.room3.Dao class * { *; }

# 保留 Database 类
-keep @androidx.room3.Database class * { *; }

# 保留 Room 生成的实现类
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class * implements androidx.room3.RoomDatabase$Callback { *; }

# ============================================================
# 2. Bugly 崩溃上报
# ============================================================
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.** { *; }

# ============================================================
# 3. DKPlayer VideoCache
# ============================================================
-keep class com.danikula.videocache.** { *; }
-keep interface com.danikula.videocache.** { *; }

# ============================================================
# 4. ViewPager2
# ============================================================
-keep class androidx.viewpager2.widget.ViewPager2 { *; }

# ============================================================
# 5. dy-player 实体类 (Kotlinx Serialization)
# ============================================================
# 保留所有 @Serializable 标注的类
-keep @kotlinx.serialization.Serializable class me.lingci.dy.player.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class me.lingci.dy.player.** {
    <fields>;
    <init>(...);
}

# 保留实体类
-keep class me.lingci.dy.player.entity.** { *; }

# 保护 StorageType 枚举值名称（valueOf/toString 依赖）
-keepclassmembers enum me.lingci.lib.base.storage.entity.StorageType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum me.lingci.dy.player.entity.MediaLibType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保护 Room TypeConverter
-keep class me.lingci.dy.player.room.MediaLibTypeConverter { *; }
-keep class me.lingci.dy.player.room.StorageTypeConverter { *; }

# ============================================================
# 6. Navigation Component
# ============================================================
-keep class * extends androidx.fragment.app.Fragment { *; }
-keepnames class * extends android.os.Parcelable
-keepnames class * extends java.io.Serializable

# ============================================================
# 7. 明确保护 Room 实体和 DAO
# ============================================================
# 保护 dy-player 模块的 Room 实体
-keep @androidx.room3.Entity class me.lingci.dy.player.entity.** { *; }
-keep class me.lingci.dy.player.entity.VideoData { *; }
-keep class me.lingci.dy.player.entity.MediaData { *; }
-keep class me.lingci.dy.player.entity.SourceData { *; }

# 保护 dy-player 模块的 DAO
-keep @androidx.room3.Dao interface me.lingci.dy.player.room.dao.** { *; }
-keep interface me.lingci.dy.player.room.dao.MediaDataDao { *; }
-keep interface me.lingci.dy.player.room.dao.SourceDataDao { *; }
-keep interface me.lingci.dy.player.room.dao.VideoDataDao { *; }

# 保护 Database 类
-keep class me.lingci.dy.player.room.AppDatabase { *; }

# ============================================================
# 8. 修复 SLF4J 缺失类报错 (AGP 9)
# ============================================================
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.LoggerFactory

# ============================================================
# 9. 通用规则
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature