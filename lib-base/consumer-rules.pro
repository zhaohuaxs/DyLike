# ============================================================
# lib-base Consumer ProGuard Rules for AGP 9 & R8
# ============================================================

# ============================================================
# 1. Kotlinx Serialization
# ============================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keep @kotlinx.serialization.Serializable class me.lingci.lib.base.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class me.lingci.lib.base.** {
    <fields>;
    <init>(...);
}

-keep class kotlinx.serialization.** { *; }
-keep class * implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================
# 2. OkHttp
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================================
# 2.1 BouncyCastle（jcifs-ng 的 MD4 Provider）
# ============================================================
# jcifs-ng 通过 JCE 按名字查找 "MD4"，Provider 内部也用类名注册摘要实现；
# R8 看不到这条引用链，release 会报 NoSuchAlgorithmException: MD4 for provider BC，
# 表现为 SMB 认证失败。目前只被 SMB 使用。
-keep class org.bouncycastle.** { *; }

# ============================================================
# 3. Glide
# ============================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}
-keepnames class com.bumptech.glide.GeneratedAppGlideModule

# ============================================================
# 4. JSoup
# ============================================================
-keeppackagenames org.jsoup.nodes
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** { *; }

# ============================================================
# 5. JCIFS-NG (SMB)
# ============================================================
-keep class jcifs.** { *; }
-keep interface jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn javax.servlet.**

# ============================================================
# 6. Lifecycle (ViewModel, LiveData)
# ============================================================
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
-keepclassmembers class ** {
    @androidx.lifecycle.OnLifecycleEvent *;
}

# ============================================================
# 7. Kotlin 反射和协程
# ============================================================
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlinx.coroutines.** { *; }

# ============================================================
# 8. 通用规则
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
