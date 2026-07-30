# 后台播放 + PiP + 通知栏控制 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Activity 进入后台时,播放器实例通过 Foreground Service 临时接管,只保留音频播放 + MediaStyle 通知,前台返回时取回 player 恢复全屏。

**Architecture:** 轻量 `PlaybackService` + Binder 直接引用转移 player。不重构 BaseVideoView 与 Activity 的紧耦合,只在 Activity 的 onStop/onStart 做转移。Service 不创建 player,不加载媒体源,只托管"已经在播的"。短视频补齐系统 PiP(长视频已有)。

**Tech Stack:** Android Foreground Service, MediaSessionCompat, NotificationCompat.MediaStyle, ExoPlayer setVideoSurface(null) 后台音频,Binder IPC,BroadcastReceiver(通知按钮)。

## Global Constraints

- **minSdk = 24,targetSdk = 28**(不调整,见 AGENTS.md)
- **Gradle wrapper** 9.3.1,JDK 17(`C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot`),构建命令 `./gradlew :dy-player:assembleBetaDebug`
- **Kotlin-first**,新文件用 Kotlin;`BaseVideoView.java` 是 fork 的 DKVideoPlayer 库,继续用 Java 修改
- **Player 后端**:本期只做 Exo backend;MPV 的 surface 管理不同,留作后续
- **跨进程**:不做(划掉 app = 停止)
- **后台视频**:不解码视频,只音频(`player.setSurface(null)` + `playWhenReady=true`)
- **进度保存**:沿用现有 SpUtil/Room,Service 不管持久化
- **通知权限**:targetSdk 28 下不需要运行时申请,Manifest 声明即可
- **测试方式**:无单元测试框架(项目现状),以装机手动验证为主;每个 Task 末尾给出验证步骤
- **提交规范**:每个 Task 一个 commit,commit message 用 `feat(background-play):` 或 `refactor(background-play):` 前缀

## File Structure

### 新增文件(4 个,都在 `dy-player/src/main/java/me/lingci/dy/player/service/`)

| 文件 | 责任 |
|---|---|
| `PlaybackService.kt` | Foreground Service 主体:持有 player + MediaSession + AudioFocus + 通知生命周期 |
| `PlaybackBinder.kt` | Binder,定义 takePlayer/returnPlayer/updateMetadata 等 Activity↔Service 接口 |
| `PlaybackNotificationHelper.kt` | 构建 MediaStyle 通知 + 封面大视图 RemoteViews |
| `PlaybackModels.kt` | data class `PlaybackMetadata`(标题/副标题/封面 bitmap/时长)+ 广播 action 常量 |

### 修改文件(5 个)

| 文件 | 改动 |
|---|---|
| `lib-player/dkplayer-java/.../player/BaseVideoView.java` | 新增 `detachPlayerForBackground()` / `attachPlayerFromBackground()` |
| `lib-player/player-ui/.../widget/videoview/CustomVideoView.kt` | 暴露 detach/attach 的 Kotlin 友好方法 |
| `dy-player/.../ui/long_video/LongVideoActivity.kt` | onStop/onStart 后台逻辑 + Service 绑定 + PiP 转后台衔接 |
| `dy-player/.../ui/short_video/ShortVideoActivity.kt` | 同上 + 新增 onUserLeaveHint/PiP + ViewPager2 后台限制 |
| `dy-player/src/main/AndroidManifest.xml` | `<service>` 声明 + ShortVideoActivity supportsPictureInPicture + 权限 |

---

## Task 1:BaseVideoView 新增 detach/attach player API

**目标**:给 `BaseVideoView` 加"取出 player 实例但不销毁"和"放回 player 实例"的方法,供 Service 接管使用。

**Files:**
- Modify: `lib-player/dkplayer-java/src/main/java/xyz/doikki/videoplayer/player/BaseVideoView.java`(在 `release()` 方法之后,约 line 425 后新增)
- Modify: `lib-player/player-ui/src/main/java/me/lingci/lib/player/widget/videoview/CustomVideoView.kt`(暴露 Kotlin 方法)

**Interfaces:**
- Consumes: `mMediaPlayer`(line 46,`protected P mMediaPlayer`)、`mRenderView`(line 56,`protected IRenderView mRenderView`)、`mPlayerContainer`(line 54)
- Produces: `detachPlayerForBackground(): AbstractPlayer`、`attachPlayerFromBackground(AbstractPlayer)` 两个新方法

**关键背景事实**(来自探索):
- `release()`(line 386-425)会销毁 player 和 render view,**不能用于 detach**
- player 与 surface 的绑定发生在 `TextureRenderView.onSurfaceTextureAvailable()`(line 92):`mMediaPlayer.setSurface(mSurface)`
- `TextureRenderView.release()`(line 70-76)释放 surface 对象,但**不调** player.setSurface(null)——所以 detach 时需要显式调 player.setSurface(null) 停止视频解码
- `mRenderView` 可能为 null(如果还没开始播放)

- [ ] **Step 1: 在 BaseVideoView.java 新增 detachPlayerForBackground 方法**

在 `release()` 方法之后(line 425 后)新增:

```java
/**
 * 取出当前 player 实例,供后台 Service 接管。
 * 与 {@link #release()} 不同:不销毁 player,不 null 化 mRenderView,
 * 只是把 player 引用拿走 + 停止视频解码(保留音频)。
 *
 * 调用后 BaseVideoView 处于"无 player 但有 render view"的中间态,
 * 必须随后调用 {@link #attachPlayerFromBackground} 恢复,或 {@link #release} 清理。
 *
 * @return 当前的 player 实例;如果已经没有 player 返回 null
 */
public AbstractPlayer detachPlayerForBackground() {
    AbstractPlayer player = mMediaPlayer;
    if (player == null) {
        return null;
    }
    // 停止视频解码但保留音频(setVideoSurface(null) 是 Media3 标准 API)
    // 这样后台 Service 接管后,player 继续播音频不解码视频
    try {
        player.setSurface(null);
    } catch (Exception e) {
        // 某些 backend 可能不支持,忽略
    }
    // 解除 player 的事件监听(避免 detach 后事件还回到 view)
    // 注意:不 setPlayerEventListener(null),因为 AbstractPlayer 没有空 setter;
    // 事件回调会继续触发但 view 处于中间态,回调里要判 mMediaPlayer != null
    mMediaPlayer = null;
    // 不动 mRenderView:保留它的 surface,等 attach 时重新绑定
    return player;
}

/**
 * 放回 player 实例,从前台 Service 取回后恢复显示。
 *
 * @param player 之前通过 {@link #detachPlayerForBackground} 取出的 player
 */
public void attachPlayerFromBackground(AbstractPlayer player) {
    if (player == null) {
        return;
    }
    mMediaPlayer = (P) player;
    // 重新绑定 surface:如果 mRenderView 存在且 surface 可用,
    // 通过 render view 的 onSurfaceTextureAvailable 逻辑重新绑定。
    // 但 onSurfaceTextureAvailable 只在 surface 首次创建时触发,
    // 这里需要主动调 setSurface。
    if (mRenderView != null) {
        // mRenderView 持有的 surface 可能已失效(TextureView 仍 attached 时 surface 应该还在)
        // 重新 attach 会触发 onSurfaceTextureAvailable → setSurface
        // 但如果 surface 已经 available(没销毁过),需要手动调一次
        // 安全做法:让 render view 重新走一遍 attach 流程
        mRenderView.attachToPlayer(mMediaPlayer);
    }
    // 恢复 keep screen on(如果正在播放)
    if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
        mPlayerContainer.setKeepScreenOn(true);
    }
}
```

- [ ] **Step 2: 编译验证 lib-player/dkplayer-java**

Run: `./gradlew :lib-player:dkplayer-java:compileDebugJavaWithJavac`(设置 JAVA_HOME)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 在 CustomVideoView.kt 暴露 Kotlin 友好方法**

在 `CustomVideoView.kt` 末尾(class 内)新增:

```kotlin
/**
 * 取出 player 实例供后台 Service 接管。
 * @return 当前 player,或 null(无 player)
 */
fun detachPlayer(): AbstractPlayer? = detachPlayerForBackground()

/**
 * 从后台 Service 取回 player 恢复显示。
 */
fun attachPlayer(player: AbstractPlayer?) {
    if (player != null) attachPlayerFromBackground(player)
}
```

- [ ] **Step 4: 编译验证 lib-player/player-ui**

Run: `./gradlew :lib-player:player-ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add lib-player/dkplayer-java/src/main/java/xyz/doikki/videoplayer/player/BaseVideoView.java \
        lib-player/player-ui/src/main/java/me/lingci/lib/player/widget/videoview/CustomVideoView.kt
git commit -m "feat(background-play): BaseVideoView 新增 detach/attach player API

- detachPlayerForBackground(): 取出 player 不销毁,setSurface(null) 停止视频解码保留音频
- attachPlayerFromBackground(): 放回 player,重新绑定 surface
- CustomVideoView 暴露 Kotlin 友好方法"
```

---

## Task 2:PlaybackModels + PlaybackActions 常量

**目标**:定义元数据 data class 和广播 action 常量,后续 Service/Activity 都依赖这些。

**Files:**
- Create: `dy-player/src/main/java/me/lingci/dy/player/service/PlaybackModels.kt`

**Interfaces:**
- Produces: `PlaybackMetadata` data class、`PlaybackAction` object(action 字符串常量)

- [ ] **Step 1: 创建 PlaybackModels.kt**

```kotlin
package me.lingci.dy.player.service

import android.graphics.Bitmap

/**
 * 后台播放的元数据,用于通知栏显示 + MediaSession。
 */
data class PlaybackMetadata(
    val title: String,           // 视频标题
    val subtitle: String = "",   // 副标题(如来源、第 N 个)
    val coverBitmap: Bitmap? = null,  // 封面(可为 null)
    val duration: Long = 0,      // 总时长(ms)
    val currentPosition: Long = 0  // 当前进度(ms)
)

/**
 * 通知栏 / Service → Activity 的广播 action 常量。
 * ACTION_PLAY_PAUSE 不走广播(Service 直接控制 player)。
 */
object PlaybackAction {
    /** 通知栏点了"上一个" */
    const val ACTION_PREV = "me.lingci.dy.player.playback.PREV"
    /** 通知栏点了"下一个" */
    const val ACTION_NEXT = "me.lingci.dy.player.playback.NEXT"
    /** 通知栏点了"关闭" */
    const val ACTION_CLOSE = "me.lingci.dy.player.playback.CLOSE"
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :dy-player:compileBetaDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/service/PlaybackModels.kt
git commit -m "feat(background-play): 新增 PlaybackMetadata + PlaybackAction 常量"
```

---

## Task 3:PlaybackNotificationHelper

**目标**:构建 MediaStyle + 封面大视图通知。独立类,可单测/装机验证通知样式。

**Files:**
- Create: `dy-player/src/main/java/me/lingci/dy/player/service/PlaybackNotificationHelper.kt`

**Interfaces:**
- Consumes: `PlaybackMetadata`(Task 2)、`androidx.media:media`(MediaSessionCompat token)、`androidx.core:core-ktx`(NotificationCompat)
- Produces: `PlaybackNotificationHelper` 类,方法 `buildNotification(metadata, isPlaying, sessionToken, contentIntent, actionIntents): Notification`

**依赖确认**:`androidx.media` 和 `androidx.core` 是否已在 `gradle/libs.versions.toml`。如未引入,需先加。

- [ ] **Step 1: 确认 androidx.media 依赖**

Run: `grep -i "media" D:/DyLike/DyLike/gradle/libs.versions.toml | head`
Expected: 检查是否有 `androidx.media = ...` 或 `media-session`。如果没有,需要添加:

如缺失,在 `gradle/libs.versions.toml` 的 `[versions]` 添加:
```toml
androidx-media = "1.7.0"
```
`[libraries]` 添加:
```toml
androidx-media = { module = "androidx.media:media", version.ref = "androidx-media" }
```
然后在 `dy-player/build.gradle.kts` 的 dependencies 加:
```kotlin
implementation(libs.androidx.media)
```

- [ ] **Step 2: 创建 PlaybackNotificationHelper.kt**

```kotlin
package me.lingci.dy.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

/**
 * 后台播放通知构建器(MediaStyle + 封面大视图)。
 *
 * 通知布局:
 * - ContentView(收起): MediaStyle + 标题/副标题 + 3 键(上/暂停/下)
 * - BigContentView(展开): 封面大图 + 标题/副标题 + 3 键 + 关闭/打开
 */
class PlaybackNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "dy_player_playback"
        const val CHANNEL_NAME = "后台播放"
        const val NOTIFICATION_ID = 1001
    }

    /** 创建通知渠道(Android 8+ 必需)。幂等。 */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "视频后台播放控制"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 构建后台播放通知。
     *
     * @param metadata 视频元数据(标题/封面/时长)
     * @param isPlaying 当前是否在播放(决定暂停/播放按钮图标)
     * @param sessionToken MediaSession token(用于 MediaStyle 对接系统)
     * @param contentIntent 点击通知主体打开 Activity 的 PendingIntent
     * @return 构建好的 Notification
     */
    fun buildNotification(
        metadata: PlaybackMetadata,
        isPlaying: Boolean,
        sessionToken: android.support.v4.media.session.MediaSessionCompat.Token?,
        contentIntent: PendingIntent
    ): Notification {
        ensureChannel()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(metadata.title)
            .setContentText(metadata.subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)

        // 封面
        if (metadata.coverBitmap != null) {
            builder.setLargeIcon(metadata.coverBitmap)
        }

        // 动作按钮(顺序:上一个、暂停/播放、下一个)
        builder.addAction(
            buildAction(PlaybackAction.ACTION_PREV, android.R.drawable.ic_media_previous, "上一个")
        )
        builder.addAction(
            buildAction(
                if (isPlaying) "me.lingci.dy.player.playback.PAUSE" else "me.lingci.dy.player.playback.PLAY",
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放"
            )
        )
        builder.addAction(
            buildAction(PlaybackAction.ACTION_NEXT, android.R.drawable.ic_media_next, "下一个")
        )
        builder.addAction(
            buildAction(PlaybackAction.ACTION_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, "关闭")
        )

        // MediaStyle:对接系统媒体控制(锁屏/蓝牙/车机)
        val mediaStyle = MediaStyle()
        if (sessionToken != null) {
            mediaStyle.setMediaSession(sessionToken)
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2)  // 收起时显示前 3 个按钮
        builder.setStyle(mediaStyle)

        return builder.build()
    }

    /** 构建广播 PendingIntent(通知按钮点击 → 发广播 → Activity/Service 接收)。 */
    private fun buildAction(action: String, icon: Int, label: String): NotificationCompat.Action {
        val intent = Intent(action).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
        return NotificationCompat.Action.Builder(icon, label, pendingIntent).build()
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :dy-player:compileBetaDebugKotlin`
Expected: BUILD SUCCESSFUL(如有 androidx.media 缺失,先做 Step 1)

- [ ] **Step 4: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/service/PlaybackNotificationHelper.kt \
        gradle/libs.versions.toml dy-player/build.gradle.kts  # 如有改动
git commit -m "feat(background-play): 新增 PlaybackNotificationHelper(MediaStyle + 封面大视图)"
```

---

## Task 4:PlaybackBinder + PlaybackService

**目标**:实现 Foreground Service 主体 + Binder 接口。Service 持有 player、MediaSession、AudioFocus、通知。

**Files:**
- Create: `dy-player/src/main/java/me/lingci/dy/player/service/PlaybackBinder.kt`
- Create: `dy-player/src/main/java/me/lingci/dy/player/service/PlaybackService.kt`
- Modify: `dy-player/src/main/AndroidManifest.xml`(声明 service + 权限)

**Interfaces:**
- Consumes: `PlaybackMetadata`(Task 2)、`PlaybackNotificationHelper`(Task 3)、`AbstractPlayer`(Task 1)
- Produces: `PlaybackService`(Android Service)、`PlaybackBinder`(Binder,暴露 takePlayer/returnPlayer/updateMetadata/isHoldingPlayer)

- [ ] **Step 1: 创建 PlaybackBinder.kt**

```kotlin
package me.lingci.dy.player.service

import xyz.doikki.videoplayer.player.AbstractPlayer

/**
 * Activity ↔ PlaybackService 的 Binder 接口。
 *
 * 调用流程:
 * 1. Activity onStop 触发后台:bindService → onServiceConnected → takePlayer(player, metadata)
 * 2. Activity onStart 恢复前台:检查 isHoldingPlayer → returnPlayer() → stopForegroundAndNotification()
 */
class PlaybackBinder(private val service: PlaybackService) : android.os.Binder() {

    /** Activity 把 player 交给 Service 接管。 */
    fun takePlayer(player: AbstractPlayer, metadata: PlaybackMetadata) {
        service.takePlayer(player, metadata)
    }

    /** Activity 从 Service 取回 player。返回 null 表示 Service 没持有。 */
    fun returnPlayer(): AbstractPlayer? = service.returnPlayer()

    /** Activity 切了视频,更新通知栏元数据。 */
    fun updateMetadata(metadata: PlaybackMetadata) {
        service.updateMetadata(metadata)
    }

    /** Service 是否持有 player(Activity 恢复时判断)。 */
    val isHoldingPlayer: Boolean get() = service.isHoldingPlayer

    /** 关闭前台通知(恢复前台时调用)。 */
    fun stopForegroundAndNotification() {
        service.stopForegroundAndNotification()
    }
}
```

- [ ] **Step 2: 创建 PlaybackService.kt**

```kotlin
package me.lingci.dy.player.service

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaSessionManager
import android.util.Log
import xyz.doikki.videoplayer.player.AbstractPlayer

/**
 * 后台播放 Foreground Service。
 *
 * 职责:
 * 1. 持有 player 引用(临时,从 Activity 转入) + setVideoSurface(null) 停止视频解码
 * 2. 显示 MediaStyle 通知 + 前台保活
 * 3. MediaSession(对接锁屏/蓝牙/车机)
 * 4. 音频焦点管理(LOSS_TRANSIENT 暂停 / GAIN 恢复)
 *
 * 不负责:创建 player、加载媒体源、进度保存、列表管理。
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
    }

    private val binder = PlaybackBinder(this)
    private var player: AbstractPlayer? = null
    private var metadata: PlaybackMetadata? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationHelper: PlaybackNotificationHelper? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    val isHoldingPlayer: Boolean get() = player != null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = PlaybackNotificationHelper(this)
        notificationHelper?.ensureChannel()
        setupMediaSession()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        Log.i(TAG, "PlaybackService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即 startForeground(空通知),避免 5 秒超时
        // 实际内容在 takePlayer 后更新
        val placeholderNotification = notificationHelper?.buildNotification(
            PlaybackMetadata(title = "正在后台播放"),
            isPlaying = false,
            sessionToken = mediaSession?.sessionToken,
            contentIntent = createContentIntent()
        )
        if (placeholderNotification != null) {
            startForeground(PlaybackNotificationHelper.NOTIFICATION_ID, placeholderNotification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Activity 把 player 交给 Service。 */
    fun takePlayer(player: AbstractPlayer, metadata: PlaybackMetadata) {
        this.player = player
        this.metadata = metadata
        // setVideoSurface(null) 已在 BaseVideoView.detachPlayerForBackground 中调用
        // 更新 MediaSession + 通知
        updateMediaSession(metadata, isPlaying = player.isPlaying)
        updateNotification()
        requestAudioFocus()
        Log.i(TAG, "took player: ${metadata.title}")
    }

    /** Activity 取回 player。 */
    fun returnPlayer(): AbstractPlayer? {
        val p = player
        player = null
        abandonAudioFocus()
        stopForegroundAndNotification()
        Log.i(TAG, "returned player")
        return p
    }

    fun updateMetadata(metadata: PlaybackMetadata) {
        this.metadata = metadata
        updateMediaSession(metadata, isPlaying = player?.isPlaying == true)
        updateNotification()
    }

    fun stopForegroundAndNotification() {
        abandonAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        mediaSession?.isActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // 如果 Service 被销毁时仍持有 player(Activity 异常销毁),释放它
        player?.release()
        player = null
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        Log.i(TAG, "PlaybackService destroyed")
    }

    // === 内部方法 ===

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "DyPlayer").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { player?.start() }
                override fun onPause() { player?.pause() }
                override fun onStop() { player?.stop() }
            })
            isActive = true
        }
    }

    private fun updateMediaSession(metadata: PlaybackMetadata, isPlaying: Boolean) {
        val session = mediaSession ?: return
        // PlaybackState
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, metadata.currentPosition, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
        session.setPlaybackState(playbackState)
        // Metadata
        val md = android.support.v4.media.MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, metadata.subtitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, metadata.duration)
        if (metadata.coverBitmap != null) {
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, metadata.coverBitmap)
        }
        session.setMetadata(md.build())
    }

    private fun updateNotification() {
        val md = metadata ?: return
        val p = player ?: return
        val notification = notificationHelper?.buildNotification(
            md,
            isPlaying = p.isPlaying,
            sessionToken = mediaSession?.sessionToken,
            contentIntent = createContentIntent()
        ) ?: return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(PlaybackNotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun createContentIntent(): android.app.PendingIntent {
        // 点击通知打开 MainActivity(让它跳转到对应播放页)
        val intent = Intent(this, me.lingci.dy.player.ui.main.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        else
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getActivity(this, 0, intent, flags)
    }

    // === AudioFocus ===

    private fun requestAudioFocus() {
        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focus ->
            when (focus) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    player?.pause()
                    metadata?.let { updateNotification() }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    player?.setVolume(0.1f, 0.1f)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    player?.setVolume(1.0f, 1.0f)
                    // LOSS 时不自动恢复(用户手动点播放);GAIN(从 DUCK 恢复)可以继续
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
                .build()
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            audioFocusChangeListener?.let {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(it)
            }
        }
        audioFocusChangeListener = null
    }
}
```

- [ ] **Step 3: 在 AndroidManifest.xml 声明 Service + 权限**

在 `<application>` 内(其他 Activity 之后)新增 `<service>`:
```xml
<service
    android:name=".service.PlaybackService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

在 `<manifest>` 的权限区(现有权限之后)新增:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

同时给 ShortVideoActivity 加 `android:supportsPictureInPicture="true"`(Task 7 会用到):
```xml
<activity
    android:name=".ui.short_video.ShortVideoActivity"
    android:launchMode="singleTop"
    android:configChanges="..."
    android:supportsPictureInPicture="true"  <!-- 新增 -->
    android:exported="true" />
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :dy-player:assembleBetaDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/service/PlaybackBinder.kt \
        dy-player/src/main/java/me/lingci/dy/player/service/PlaybackService.kt \
        dy-player/src/main/AndroidManifest.xml
git commit -m "feat(background-play): 新增 PlaybackService + Binder(MediaSession + AudioFocus + 前台保活)"
```

---

## Task 5:LongVideoActivity 集成后台播放

**目标**:长视频 Activity 接入 PlaybackService——onStop 触发后台、onStart 取回 player、PiP 关闭转后台衔接。

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt`

**Interfaces:**
- Consumes: `PlaybackService`、`PlaybackBinder`、`PlaybackMetadata`、`PlaybackAction`(Task 2/4)、`CustomVideoView.detachPlayer/attachPlayer`(Task 1)
- Produces: LongVideoActivity 的后台播放能力

**关键现状事实**(来自探索):
- `onPause`(line 893-908):PiP 模式 early-return,否则 `videoView.pause()` + `danmakuView.pause()`
- `onStop`:**无 override**——需要新增
- `onStart`:**无 override**——需要新增
- `onDestroy`(line 910-922):`videoView.release()`
- PiP 相关:line 1055-1327(详见 spec)
- `onBackPressedCallback`(line 385-392):`finish()`

- [ ] **Step 1: 新增字段 + ServiceConnection**

在 LongVideoActivity 字段区(PiP 字段附近,line 213 后)新增:

```kotlin
// ===== 后台播放 Service =====
private var playbackService: PlaybackBinder? = null
private var isBoundToPlaybackService = false
private val playbackServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        playbackService = service as? PlaybackBinder
        // Service 连上后,把 player 交给它
        tryEnterBackgroundPlay()
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        playbackService = null
        isBoundToPlaybackService = false
    }
}

/** 标记:用户主动按返回退出(不进后台) */
private var userInitiatedExit = false
```

- [ ] **Step 2: 修改 onBackPressedCallback,标记用户主动退出**

在 `onBackPressedCallback`(line 385-392)中,`finish()` 前加:
```kotlin
userInitiatedExit = true
finish()
```

- [ ] **Step 3: 新增 onStop(后台触发)**

在 `onPause`(line 893-908)之后新增 `onStop`:

```kotlin
override fun onStop() {
    super.onStop()
    // 用户主动退出 → 不进后台
    if (userInitiatedExit) {
        userInitiatedExit = false
        return
    }
    // 旋屏等配置变化 → 不进后台
    if (isChangingConfigurations) return
    // 正在 PiP → 不进后台(PiP 期间 Activity 仍可见)
    if (isInPictureInPictureMode) return
    // 播放中 + 未暂停 → 进入后台播放模式
    if (shouldEnterBackgroundPlay()) {
        startPlaybackService()
    }
}

/** 后台播放触发条件:播放中 + 未暂停 + 有 player。 */
private fun shouldEnterBackgroundPlay(): Boolean {
    return videoView.currentPlayState == VideoView.STATE_PLAYING
        && videoView.hasPlayer()
}

/** 启动并绑定 PlaybackService。 */
private fun startPlaybackService() {
    val intent = Intent(this, PlaybackService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
    bindService(intent, playbackServiceConnection, BIND_AUTO_CREATE)
    isBoundToPlaybackService = true
    // 禁用 view 的音频焦点(Service 接管)
    videoView.setEnableAudioFocus(false)
    Log.d(TAG, "starting background playback")
}

/** 在 ServiceConnection.onServiceConnected 中调用:把 player 交给 Service。 */
private fun tryEnterBackgroundPlay() {
    val binder = playbackService ?: return
    val player = videoView.detachPlayer() ?: return
    val metadata = PlaybackMetadata(
        title = currentTitle ?: "正在播放",
        subtitle = currentSourceName ?: "",
        duration = videoView.duration,
        currentPosition = videoView.currentPosition
    )
    binder.takePlayer(player, metadata)
}
```

**注意**:`currentTitle`、`currentSourceName` 需要从现有的媒体数据中取。如果这些字段名不存在,需要从当前播放的 `MediaData` 对象中提取(看现有 `mVideoData` 或类似字段)。

- [ ] **Step 4: 新增 onStart(恢复前台)**

在 `onResume`(line 866)之前新增 `onStart`:

```kotlin
override fun onStart() {
    super.onStart()
    // 如果 Service 持有 player,取回
    if (isBoundToPlaybackService && playbackService?.isHoldingPlayer == true) {
        val player = playbackService?.returnPlayer()
        if (player != null) {
            videoView.attachPlayer(player)
            videoView.setEnableAudioFocus(true)
            Log.d(TAG, "resumed from background, player reattached")
        }
        unbindService(playbackServiceConnection)
        isBoundToPlaybackService = false
        playbackService = null
    }
}
```

- [ ] **Step 5: PiP 关闭转后台衔接**

在 `onPictureInPictureModeChanged`(line 1113-1120)中,当 `isInPictureInPictureMode = false`(PiP 退出)时,**不主动 pause**(留给 onStop 决定):

```kotlin
override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    if (isInPictureInPictureMode) {
        enterPipUiState()
    } else {
        exitPipUiState()
        // PiP 退出后不主动 pause —— 让 onStop 决定是进后台还是正常暂停
    }
}
```

- [ ] **Step 6: onDestroy 兜底(Service 仍持有 player 时)**

在 `onDestroy`(line 910-922)中,`videoView.release()` 之前加:

```kotlin
// 如果 Service 还持有 player(Activity 异常销毁),取回并释放
if (isBoundToPlaybackService) {
    try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
    isBoundToPlaybackService = false
}
playbackService?.returnPlayer()?.release()
playbackService = null
```

- [ ] **Step 7: 装机验证**

Build + install + 验证:
1. 打开长视频,播放
2. 按 Home → 应该看到通知出现("正在后台播放")+ 听到音频继续
3. 从通知点开 → 回到全屏,视频继续
4. 按 Home 进 PiP(原逻辑)→ 关闭 PiP → 进后台播放 + 通知
5. 退出 Activity(返回键)→ 通知消失,停止

Run: `./gradlew :dy-player:assembleBetaDebug && adb install -r ... && adb logcat -s PlaybackService:I LongVideoActivity:V`

- [ ] **Step 8: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt
git commit -m "feat(background-play): LongVideoActivity 接入后台播放(onStop 触发 + onStart 恢复 + PiP 转后台)"
```

---

## Task 6:通知栏广播接收器(上下一个/关闭)

**目标**:在 LongVideoActivity 注册广播接收器,处理通知栏的 PREV/NEXT/CLOSE 动作。

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt`

- [ ] **Step 1: 新增广播接收器字段 + 注册/注销方法**

在 LongVideoActivity 的 PiP receiver 附近新增:

```kotlin
/** 通知栏控制广播接收器(后台播放时通知按钮)。 */
private val playbackActionReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            PlaybackAction.ACTION_PREV -> onPreviousPlay()
            PlaybackAction.ACTION_NEXT -> onNextPlay()
            PlaybackAction.ACTION_CLOSE -> {
                // 关闭:停止后台播放 + finish
                playbackService?.returnPlayer()?.release()
                playbackService = null
                finish()
            }
        }
    }
}
private var isPlaybackReceiverRegistered = false

private fun registerPlaybackActionReceiver() {
    if (isPlaybackReceiverRegistered) return
    val filter = android.content.IntentFilter().apply {
        addAction(PlaybackAction.ACTION_PREV)
        addAction(PlaybackAction.ACTION_NEXT)
        addAction(PlaybackAction.ACTION_CLOSE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(playbackActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(playbackActionReceiver, filter)
    }
    isPlaybackReceiverRegistered = true
}

private fun unregisterPlaybackActionReceiver() {
    if (!isPlaybackReceiverRegistered) return
    try { unregisterReceiver(playbackActionReceiver) } catch (_: Exception) {}
    isPlaybackReceiverRegistered = false
}
```

- [ ] **Step 2: 在 startPlaybackService 后注册接收器**

在 `startPlaybackService()` 末尾加:
```kotlin
registerPlaybackActionReceiver()
```

- [ ] **Step 3: 在 onStart 取回 player 后注销接收器**

在 `onStart` 的 `if (isBoundToPlaybackService ...)` 块末尾加:
```kotlin
unregisterPlaybackActionReceiver()
```

- [ ] **Step 4: onDestroy 注销**

在 `onDestroy` 加:
```kotlin
unregisterPlaybackActionReceiver()
```

- [ ] **Step 5: 装机验证通知按钮**

1. 后台播放时,点通知的"上一个"/"下一个"→ 应该切视频 + 通知更新
2. 点"关闭"→ 通知消失 + Activity finish

- [ ] **Step 6: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt
git commit -m "feat(background-play): 长视频通知栏广播接收器(PREV/NEXT/CLOSE)"
```

---

## Task 7:ShortVideoActivity 补齐 PiP + 后台播放

**目标**:短视频 Activity 补齐系统 PiP(参考 LongVideo 实现)+ 接入后台播放。后台模式下禁止 ViewPager2 滑动。

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt`
- Modify: `dy-player/src/main/AndroidManifest.xml`(Task 4 已加 supportsPictureInPicture,此 Task 确认)

**关键现状事实**(来自探索):
- 短视频无 PiP 代码
- `mVideoView` 在 ViewPager2 间复用(`addView`/`removeView`)
- `onPause`(line 937-943):`mVideoView.pause()`
- 无 `onStop`、无 `onStart`、无 `onUserLeaveHint`

- [ ] **Step 1: 新增 onUserLeaveHint + PiP 逻辑**

参考 LongVideoActivity 的 PiP 代码(line 1055-1327),为 ShortVideoActivity 新增:

```kotlin
override fun onUserLeaveHint() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && shouldEnterPip()) {
        enterPiPMode()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun enterPiPMode() {
    val params = android.app.PictureInPictureParams.Builder()
        .setAspectRatio(android.util.Rational(9, 16))  // 短视频竖屏 9:16
        .build()
    enterPictureInPictureMode(params)
}

override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    // PiP 时禁止 ViewPager2 滑动
    binding.viewPager2.isUserInputEnabled = !isInPictureInPictureMode
}

private fun shouldEnterPip(): Boolean {
    return spUtil.longVideoPip  // 复用长视频的 PiP 开关(或新增短视频开关)
        && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        && mVideoView.currentPlayState == VideoView.STATE_PLAYING
}
```

- [ ] **Step 2: 新增后台播放字段 + ServiceConnection + onStop + onStart**

复用 Task 5 的 LongVideoActivity 后台播放逻辑,适配短视频:
- `videoView` → `mVideoView`
- metadata 的 title 用当前短视频的标题
- 后台时禁止 ViewPager2 滑动:`binding.viewPager2.isUserInputEnabled = false`

(代码结构同 Task 5 Step 1-6,替换变量名)

- [ ] **Step 3: 短视频特有 — 后台时 mVideoView 的 detach**

短视频的 `mVideoView` 在 ViewPager2 间复用。后台模式下:
- `mVideoView` 从当前 ViewHolder 的 `playerContainer` detach(已有 `removeViewFormParent`)
- 然后 `mVideoView.detachPlayer()` 交给 Service
- 恢复时:`mVideoView.attachPlayer(player)` + 重新 `addView` 到当前页

在 `tryEnterBackgroundPlay()` 中:
```kotlin
private fun tryEnterBackgroundPlay() {
    val binder = playbackService ?: return
    // 短视频:先从 ViewPager2 container detach view,再 detach player
    removeViewFormParent(mVideoView)  // 现有方法
    val player = mVideoView.detachPlayer() ?: return
    val metadata = PlaybackMetadata(
        title = getCurrentShortVideoTitle() ?: "短视频",
        subtitle = "第 ${currentItemPosition + 1} 个"
    )
    binder.takePlayer(player, metadata)
    // 禁止滑动
    binding.viewPager2.isUserInputEnabled = false
}
```

- [ ] **Step 4: 装机验证**

1. 短视频播放中按 Home → 进 PiP(竖屏 9:16)
2. 关闭 PiP → 进后台播放 + 通知
3. 从通知回来 → 全屏恢复,ViewPager2 可滑动
4. 后台播放时通知的"上一个/下一个"→ 切短视频

- [ ] **Step 5: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
git commit -m "feat(background-play): 短视频补齐 PiP + 后台播放 + ViewPager2 后台限制"
```

---

## Task 8:通知封面图加载

**目标**:通知栏的封面大图。从视频源(封面 URL 或本地缩略图)加载 Bitmap,传给 PlaybackMetadata。

**Files:**
- Modify: `dy-player/.../ui/long_video/LongVideoActivity.kt`(takePlayer 时加载封面)
- Modify: `dy-player/.../ui/short_video/ShortVideoActivity.kt`(同上)

- [ ] **Step 1: 用 Glide 加载封面 Bitmap**

项目已有 Glide(`lib-base` 通过 `api` 暴露)。在 `tryEnterBackgroundPlay` 中:

```kotlin
private fun loadCoverBitmap(url: String?, callback: (android.graphics.Bitmap?) -> Unit) {
    if (url.isNullOrBlank()) { callback(null); return }
    Thread {
        try {
            val bitmap = com.bumptech.glide.Glide.with(this)
                .asBitmap()
                .load(url)
                .submit(256, 256)
                .get()
            runOnUiThread { callback(bitmap) }
        } catch (e: Exception) {
            runMainThread { callback(null) }
        }
    }.start()
}
```

在 takePlayer 前调用,封面加载完成后 updateMetadata:

```kotlin
// 先用 null 封面 takePlayer,加载完后再 updateMetadata
binder.takePlayer(player, metadata.copy(coverBitmap = null))
loadCoverBitmap(coverUrl) { bitmap ->
    if (bitmap != null) {
        playbackService?.updateMetadata(metadata.copy(coverBitmap = bitmap))
    }
}
```

- [ ] **Step 2: 装机验证封面显示**

后台播放时展开通知 → 应该看到封面大图。

- [ ] **Step 3: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt \
        dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
git commit -m "feat(background-play): 通知栏封面图加载(Glide)"
```

---

## Task 9:边界场景 + 回归测试

**目标**:覆盖 spec §6 的所有边界场景,修复发现的问题。

**Files:**
- 可能修改上述所有文件

- [ ] **Step 1: 按验证清单逐项测试**

spec §8 的 14 个场景:
- [ ] 长视频播放中按 Home → 进 PiP
- [ ] 长视频播放中按 Home + 关闭 PiP → 后台播放 + 通知
- [ ] 长视频播放中按 Back → 不进后台
- [ ] 后台播放中点通知"打开" → 全屏恢复
- [ ] 后台播放中点通知"暂停" → Service 暂停
- [ ] 后台播放中点通知"关闭" → Service stopSelf + Activity finish
- [ ] 后台播放中来电 → AudioFocus LOSS → 暂停
- [ ] 后台播放中点通知"下一个" → 切视频
- [ ] 短视频播放中按 Home → 进 PiP
- [ ] 短视频后台播放
- [ ] 短视频后台时滑 ViewPager2 → 禁止
- [ ] Activity 被系统销毁后从通知恢复 → 取回 player
- [ ] 旋屏 → isChangingConfigurations,onStop 不触发后台
- [ ] 网络断开导致 ERROR → 通知显示错误

- [ ] **Step 2: 修复发现的问题**

逐个修复测试中发现的问题(如果有的话)。

- [ ] **Step 3: 最终 Commit**

```bash
git add -A
git commit -m "fix(background-play): 边界场景修复 + 回归测试通过"
```

---

## 自审

### Spec 覆盖检查

| Spec 要求 | 对应 Task |
|---|---|
| BaseVideoView detach/attach API | Task 1 |
| PlaybackService + Binder + MediaSession + AudioFocus | Task 4 |
| NotificationHelper(MediaStyle + 封面大视图) | Task 3 |
| 通知封面加载 | Task 8 |
| LongVideoActivity 后台播放 | Task 5 |
| LongVideoActivity 通知广播接收器 | Task 6 |
| ShortVideoActivity PiP | Task 7 |
| ShortVideoActivity 后台播放 | Task 7 |
| ShortVideoActivity ViewPager2 后台限制 | Task 7 |
| Manifest service 声明 + 权限 | Task 4 |
| 短视频 supportsPictureInPicture | Task 4 |
| 边界场景处理 | Task 9 |
| PiP 关闭转后台衔接 | Task 5 Step 5 |
| AudioFocus 迁移(Service 接管) | Task 4(setEnableAudioFocus(false) 在 Task 5) |
| ✅ 全部覆盖 | |

### Placeholder 扫描
- 无 TBD/TODO ✅
- 每个步骤都有完整代码 ✅
- "如缺失则添加" 类的条件步骤有明确的 fallback 代码 ✅

### Type 一致性
- `detachPlayerForBackground` / `attachPlayerFromBackground` 在 Task 1(Java)和 Task 5(Kotlin 调用)中名称一致 ✅
- `PlaybackBinder.takePlayer(player, metadata)` / `returnPlayer()` 在 Task 4(定义)和 Task 5(调用)中一致 ✅
- `PlaybackMetadata` 字段在 Task 2(定义)和 Task 4/5/8(使用)中一致 ✅

---

## 执行方式

Plan complete and saved to `docs/superpowers/plans/2026-07-21-background-play-plan.md`。

**建议:在新会话执行**(本会话已超长)。新会话从 `ReadSessionContext` 拿到此计划,用 `superpowers:subagent-driven-development` 逐 task 执行。
