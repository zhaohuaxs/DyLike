# 后台播放 + PiP + 通知栏控制 设计

**日期**: 2026-07-21
**状态**: 已批准,待出实施计划
**关联模块**: `dy-player`, `lib-player/dkplayer-java`

## 1. 目标与范围

### 目标
实现类似 B 站的"后台继续播放"体验:用户按 Home 或退出播放页时,视频以音频形式在后台继续播放,通知栏提供媒体控制(暂停/上下一个/关闭),并显示 MediaStyle + 封面大视图。系统 PiP(画中画)用于"小窗继续看视频"的场景。

### 范围

**包含**:
- 长视频 + 短视频均支持后台音频播放
- 长视频 + 短视频均支持系统 PiP(短视频当前缺失,需补齐)
- 通知栏:MediaStyle + 封面大视图 + 标准三键(上/下/暂停)
- MediaSession(对接锁屏/蓝牙/车机)
- 前台 Service 保活(Activity 销毁后 Service 继续播放)
- Activity 与 Service 间的播放器实例临时转移

**不包含(明确排除)**:
- MPV backend 的后台播放(本期只做 Exo;MPV 的 surface 管理不同)
- app 内自定义浮窗(用系统 PiP)
- 跨进程后台播放(划掉 app 继续,已确认不做)
- 后台视频解码(只音频)
- 锁屏控件自定义 UI(用系统 MediaStyle)
- 进度持久化重构(沿用现有 SpUtil/Room)

## 2. 现状分析

### 已有基础

| 能力 | 长视频 | 短视频 |
|---|---|---|
| 系统 PiP | ✅ 已实现(`LongVideoActivity`,onUserLeaveHint + enterPictureInPictureMode + RemoteAction 三键) | ❌ 完全没有 |
| 后台音频 | ❌ | ❌ |
| 通知栏控制 | ❌ | ❌ |
| MediaSession | ❌ | ❌ |
| Service | ❌(项目无任何 Service) | ❌ |

### 关键架构事实(来自探索)

1. **`BaseVideoView` 是 fork 的 DKVideoPlayer**,与 Activity 紧耦合(`getDecorView()`、fullscreen 切换用 Activity 的 Window)。不重构库本身,只在 Activity 层做播放器转移。
2. **现有 `onPause` 已经是 `pause()` 不是 `release()`**——player 实例在 onPause 后存活,为转移提供了基础。
3. **`ExoPlayer.setVideoSurface(null)` + `playWhenReady=true`** = 只播音频不解码视频(Media3 标准行为,代码无 override 破坏)。
4. **`AudioFocusHelper`** 当前在 `LOSS_TRANSIENT` 时会暂停 player——Service 接管后要禁用 view 的音频焦点(`setEnableAudioFocus(false)`),否则双重管理冲突。
5. **`VideoViewManager` 单例**存在但未使用——不需要它,Service 直接持有 player 引用。
6. **targetSdk=28**(AGENTS.md 明确"不调整"):不需要 `POST_NOTIFICATIONS` 运行时权限;前台服务限制通过 `startForegroundService` + 5 秒内 `startForeground` 规避。

## 3. 架构设计

### 3.1 方案选择:轻量 Service + 临时转移(方案 A 增强)

**核心思路**:引入一个轻量 `PlaybackService`(Foreground Service),在 Activity 进入后台时**临时接管 player 实例**;Activity 恢复前台时**取回 player**。Service 不负责创建 player 或加载媒体源,只托管"已经在播的"。

**对比其他方案**:
- 纯 Activity 方案(不引入 Service):Activity 销毁后播放中断,体验不完整。
- 标准 Service 架构(Service 永久持有 player):需要重构 BaseVideoView 与 Activity 的紧耦合,工作量 2-4 周。
- **本方案(临时转移)**:改动可控(7-10 天),Activity 销毁后 Service 继续播放,满足需求。

### 3.2 组件图

```
┌─────────────────────────────────────────────────────┐
│  PlaybackService (Foreground Service)               │
│  ├── 持有 player 引用(临时,从 Activity 转入)         │
│  ├── MediaSession(给系统媒体控制 + 通知)              │
│  ├── NotificationHelper(MediaStyle + 封面大视图)     │
│  └── AudioFocus 管理(从 BaseVideoView 移过来)         │
└────────────┬────────────────────────┬───────────────┘
             │ Binder                 │
             ▼                        ▼
┌────────────────────────┐  ┌────────────────────────┐
│  LongVideoActivity     │  │  ShortVideoActivity    │
│  - onStop 触发后台       │  │  - 补 onUserLeaveHint   │
│  - onStart 取回 player  │  │  - onStop 触发后台       │
│  - 现有 PiP 逻辑保留     │  │  - supportsPiP=true     │
└────────────────────────┘  └────────────────────────┘
```

### 3.3 工作流状态机

```
                用户按 Home(播放中)
    ┌─────────────────────────────────────┐
    │                                     ▼
[前台播放] ◄──── onStart 取回 player ──── [后台播放]
    │                                     │
    │ 用户按 Home 且开启 PiP                │ Service 持有 player
    ▼                                     │ setVideoSurface(null)
[系统 PiP] ──── onStop(PiP 关闭) ────────►│ 继续音频解码
                                          │
                                          │ 用户划掉任务
                                          ▼
                                       [停止]
```

**后台模式触发条件**(在 `onStop` 中判断):
- 播放中 && 用户未主动暂停 && 不在 PiP 模式 && `!isChangingConfigurations()` && `onStop` 进入

> 注:"不在 PiP 模式"指的是 onStop 调用瞬间 Activity 不在 PiP。PiP 关闭流程是先 `onPictureInPictureModeChanged(false)` 然后触发 onStop——此时 PiP 已退出,条件成立,正常进入后台模式。

**动作**:
- 启动 PlaybackService(Foreground)
- 将播放器通过 Binder 传递给 Service,Service 设置 `videoSurface = null`
- 显示 MediaStyle 通知(标题+封面+播放控制)
- Activity 可安全 release player 引用(Service 持有)

**恢复前台**:
- `onStart` / `onResume` 时若 Service 仍在播放,重新绑定播放器和 Surface,关闭通知/Service

**PiP 关闭转后台**:
- `onStop` 中检查是否来自 PiP 关闭 && 仍在播放 → 进入后台模式

## 4. 组件详细设计

### 4.1 BaseVideoView 扩展(detach/attach player)

现状 `BaseVideoView` 没有"取出 player 实例"的 API——`release()` 是销毁,不是转移。需要新增两个方法:

```java
// BaseVideoView.java 新增
public AbstractPlayer detachPlayer() {
    AbstractPlayer p = mMediaPlayer;
    mMediaPlayer = null;
    // 不调 player.release(),只是拿走引用
    // 清理 surface 关联(避免 player 还在画 surface)
    if (mRenderView != null) {
        mRenderView.attachToPlayer(null);
    }
    return p;
}

public void attachPlayer(AbstractPlayer player) {
    mMediaPlayer = player;
    // 重新接 surface
    if (mRenderView != null) {
        mRenderView.attachToPlayer(player);
    }
}
```

**注意事项**:
- Exo 和 MPV 两个 backend 的 player 类型不同(`CustomExoMediaPlayer` vs `MpvMediaPlayer`),detach/attach 要保证类型一致。
- 本期只做 Exo backend 的后台播放;MPV 的 surface 管理不同,留作后续。

### 4.2 PlaybackService

#### 职责边界

**Service 负责**:
1. 持有 player 引用(临时) + `setVideoSurface(null)`
2. 显示 MediaStyle 通知 + 前台保活
3. MediaSession(对接系统媒体控制:锁屏/蓝牙/车机)
4. 音频焦点管理(`LOSS_TRANSIENT` 暂停 / `GAIN` 恢复)

**Service 不负责**:
- 创建 player(Activity 创建并初始化,只把引用交过来)
- 加载媒体源 / 切换视频(Activity 负责,Service 只托管"已经在播的")
- 进度保存(沿用现有 SpUtil/Room)
- 列表管理(上下一个视频由 Activity 处理,通知按钮发广播给 Activity)

#### 生命周期

```
Activity onStop (后台触发)
    │
    ▼
startForegroundService(PlaybackService)  ← 必须在 5 秒内调 startForeground
    │
    ▼
PlaybackService.onCreate()
    ├── 创建 MediaSession
    ├── 创建 NotificationChannel
    └── 绑定 AudioFocus

PlaybackService.onStartCommand()
    └── startForeground(NOTIFICATION_ID, buildNotification())  ← 5 秒内

PlaybackService.onBind() → 返回 LocalBinder

Activity.onServiceConnected()
    └── service.takePlayer(player)  ← Activity 把 player 交出去
        Service 内部:
          ├── mPlayer = player
          ├── player.setVideoSurface(null)  ← 停止解码视频
          ├── 接管 AudioFocus(requestAudioFocus)
          └── 更新通知(标题/封面/状态)
```

#### 通知设计(MediaStyle + 封面大视图)

**ContentView(收起)**:`NotificationCompat.MediaStyle`
```
┌──────────────────────────────────┐
│ [封面] 视频标题                    │
│        简介文字                    │
│ [⏮]  [⏸]  [⏭]                    │
└──────────────────────────────────┘
```

**BigContentView(展开)**:RemoteViews 自定义布局
```
┌──────────────────────────────────┐
│ [大封面]                          │
│ 视频标题                          │
│ 简介文字                          │
│ [⏮]  [⏸]  [⏭]                    │
│ [×关闭]            [▶ 打开 app]   │
└──────────────────────────────────┘
```

**通知点击**:PendingIntent 打开对应的 Activity(LongVideo/ShortVideo)

**通知动作(通知按钮发广播给 Activity)**:
- 上一个/下一个:Activity 收到广播后切视频(短视频切 ViewPager2 位置,长视频切列表项)
- 暂停/播放:Service 直接控制 player + 更新通知
- 关闭:`stopSelf()` + Activity 收到广播后 finish + release player

#### MediaSession

```kotlin
val mediaSession = MediaSessionCompat(this, "DyPlayer")
mediaSession.setFlags(FLAG_HANDLES_MEDIA_BUTTONS or FLAG_HANDLES_TRANSPORT_CONTROLS)
mediaSession.setCallback(object : Callback() {
    onPlay() / onPause() / onSkipToNext() / onSkipToPrevious() / onStop()
})
mediaSession.setPlaybackState(...)  // STATE_PLAYING/PAUSED + 当前 actions
mediaSession.setMetadata(...)       // 标题/艺术家/封面/时长/进度

NotificationCompat.MediaStyle()
    .setMediaSession(mediaSession.sessionToken)
    .setShowActionsInCompactView(0, 1, 2)
```

### 4.3 Activity ↔ Service 通信协议

**Activity → Service(Binder 直引)**:
- `takePlayer(player, metadata)` —— 转移 player + 当前视频元数据(标题/封面/时长)
- `returnPlayer(): AbstractPlayer?` —— 取回 player
- `updateMetadata(metadata)` —— Activity 切了视频,通知 Service 更新通知栏
- `serviceIsPlaying(): Boolean` —— 查询 Service 是否在播(用于 onStart 判断)

**Service → Activity(广播)**:
- `ACTION_NEXT` / `ACTION_PREV` —— 通知栏点了上下一个
- `ACTION_CLOSE` —— 通知栏点了关闭(用户想停止)
- `ACTION_PLAY_PAUSE` —— Service 直接控制 player(不走广播)

#### 关键状态同步:`isHoldingPlayer` 防双重控制

Service 有一个 boolean `isHoldingPlayer`:
- `true` = Service 持有 player,Activity **不应该**操作 player
- `false` = Activity 持有 player,Service 只做空转

Activity 在 `onStart` 检查:
```kotlin
if (playbackService?.isHoldingPlayer == true) {
    val player = service.returnPlayer()
    videoView.attachPlayer(player)
    service.stopForegroundAndNotification()
}
```

### 4.4 LongVideoActivity 改造

**新增字段**:
```kotlin
private var playbackService: PlaybackService?
private val serviceConnection = object : ServiceConnection { ... }
```

**`onStop()` 改造**:新增后台播放触发逻辑(见 §3.3 触发条件 + 动作)

**`onStart()` 改造**:检查 Service 是否持有 player,若有则取回 + 重新接 surface

**现有 PiP 逻辑保留**:
- `onPictureInPictureModeChanged(false)` 时不主动 pause(留给 onStop 决定)
- onStop 检测到"从 PiP 退出 && 仍在播放" → 进入后台模式

**`onDestroy()` 兜底**:如果 Service 还持有 player,returnPlayer 然后 release,防止泄漏

### 4.5 ShortVideoActivity 改造

**新增**:
- Manifest 加 `android:supportsPictureInPicture="true"`
- `onUserLeaveHint()` 新增(参考 LongVideo 实现)
- `enterPiPMode()` / `onPictureInPictureModeChanged()` 新增
- PiP 时禁止 ViewPager2 滑动(避免 player 在 PiP 时被 re-parent)
- `onStop()` / `onStart()` 同 LongVideo 的后台逻辑

**短视频特有问题**:`mVideoView` 在 ViewPager2 间复用。转移到 Service 时:
- 后台模式下 `mVideoView` 从当前 ViewHolder 的 `playerContainer` detach
- 恢复前台时,重新 addView 到当前页的 `playerContainer`(注意 ViewPager2 当前位置可能变了)
- 后台模式下短视频列表**禁止滑动**(用户如想切视频,必须先从通知返回 app,退出后台模式后再滑)。理由:滑动需要播放新视频,与后台模式只托管当前 player 的设计冲突;允许滑动会让 Service 持有的 player 失效。

## 5. 错误处理与降级

| 场景 | 触发条件 | 降级动作 |
|---|---|---|
| Service startForeground 超时 | onStartCommand 后 5 秒未调 startForeground | Service.onCreate 立即 startForeground(空通知),后续再更新内容 |
| 通知渠道创建失败 | Android 8+ NotificationChannel 异常 | try/catch,降级为普通通知(无 MediaStyle) |
| takePlayer 时 player 为 null | Activity 异常状态下 player 已 release | Service 不持有,直接 stopSelf,不显示通知 |
| returnPlayer 时 Service 已销毁 | Activity 恢复时 Service 已被系统杀 | Activity 重新 startPlay(从已保存进度恢复) |
| MediaSession 创建失败 | 极少见 | 跳过 MediaSession,只用通知按钮 |
| AudioFocus 永久丢失(LOSS) | 另一个 app 抢占(如电话) | Service 暂停 + 更新通知 + 不自动恢复 |
| 后台播放时媒体源出错 | 网络断开 / 流媒体 timeout | player 进 ERROR 状态 → 通知显示"播放错误" → stopSelf + 通知 Activity |

### 与现有"后台恢复"机制的关系

LongVideoActivity 已有"后台恢复"机制(`isReturningFromBackground` + `BACKGROUND_RECOVERY_WINDOW_MS = 5000L`)处理从后台返回后 STATE_ERROR 的自动重试。**不冲突**:新机制让 player 在后台继续播(不进 ERROR),但如果后台网络断了导致 ERROR,现有重试机制仍然适用。两个机制独立工作。

## 6. 边界场景

| 场景 | 处理 |
|---|---|
| 后台时用户点通知"打开 app" | 通知 PendingIntent → Activity onNewIntent → onStart → 取回 player |
| 后台时用户点通知"关闭" | 广播 ACTION_CLOSE → Service.stopSelf + Activity.finish(如果还活着) |
| 后台时用户点通知"暂停" | Service 直接控制 player,更新通知 |
| 后台时来电(AudioFocus LOSS) | Service 暂停,更新通知,等 GAIN 恢复 |
| 后台时 Activity 被系统销毁 | Service 继续持有 player;用户从通知回来时 Activity 重建,取回 player + 简化 UI |
| 后台时用户切视频(不可能) | 后台模式禁止列表滑动/切视频 |
| PiP 转后台(onStop from PiP exit) | 正常进入后台模式 |
| 配置变化(旋屏) | isChangingConfigurations,onStop 不触发后台 |
| Activity 被系统销毁后从通知恢复 | Service 仍在,取回 player;ViewPager2 历史不恢复(可接受) |

## 7. targetSdk 28 约束

| 项 | targetSdk 28 行为 | 应对 |
|---|---|---|
| 前台服务限制 | API 26+ 后台启动 Service 受限,但 startForegroundService 在 onStop 时(限制宽限期内)可用 | 在 onStop 触发 |
| 后台音频 | 无限制 | 天然支持 |
| 通知权限 | 不需要运行时申请(targetSdk<33) | Manifest 声明即可 |
| MediaSession | 正常工作 | 用 MediaSessionCompat |
| PiP | API 26+ 正常 | 现有逻辑已适配 |

## 8. 测试策略

| 测试场景 | 预期行为 |
|---|---|
| 长视频播放中按 Home | 进 PiP(现有逻辑,不受影响) |
| 长视频播放中按 Home + 关闭 PiP | PiP 关闭 → onStop → 后台播放 + 通知出现 |
| 长视频播放中按 Back | Activity finish,不进后台(用户主动退出) |
| 后台播放中点通知"打开" | Activity onStart → 取回 player → 全屏继续播 |
| 后台播放中点通知"暂停" | Service 暂停,通知变"播放"按钮 |
| 后台播放中点通知"关闭" | Service stopSelf + Activity finish |
| 后台播放中来电 | AudioFocus LOSS → Service 暂停,不自动恢复 |
| 后台播放中点通知"下一个" | 广播 → Activity 切视频 → 更新 Service 元数据 |
| 短视频播放中按 Home | 进 PiP(新增逻辑) |
| 短视频后台播放 | 同长视频 |
| 短视频后台时滑 ViewPager2 | 禁止滑动(或先退出后台再滑) |
| Activity 被系统销毁后从通知恢复 | Service 仍在,取回 player,简化 UI |
| 旋屏 | isChangingConfigurations,onStop 不触发后台 |
| 网络断开导致 ERROR | 通知显示错误,Service 停止 |

## 9. 改动清单

### 新增文件(4 个)

| 文件 | 模块 | 说明 |
|---|---|---|
| `PlaybackService.kt` | `dy-player/.../service/` | Foreground Service,持有 player + MediaSession + 通知 |
| `PlaybackBinder.kt` | `dy-player/.../service/` | Binder,定义 takePlayer/returnPlayer/updateMetadata |
| `PlaybackNotificationHelper.kt` | `dy-player/.../service/` | MediaStyle 通知构建 + RemoteViews 大视图 |
| `PlaybackActions.kt` | `dy-player/.../service/` | 广播 action 常量 + 元数据 data class |

### 修改文件(5 个)

| 文件 | 改动 |
|---|---|
| `BaseVideoView.java` | 新增 `detachPlayer()` / `attachPlayer()` 两个方法 |
| `LongVideoActivity.kt` | onStop/onStart 后台逻辑 + Service 绑定 + PiP 转后台衔接 |
| `ShortVideoActivity.kt` | 同上 + 新增 onUserLeaveHint/PiP 逻辑 + ViewPager2 后台限制 |
| `AndroidManifest.xml` | `<service>` 声明 + `ShortVideoActivity supportsPictureInPicture=true` + 权限 |
| `SpUtil.kt` | 可选:新增"后台播放开关"设置项 |

## 10. 工作量估算

| 阶段 | 工时 |
|---|---|
| BaseVideoView detach/attach API | 0.5 天 |
| PlaybackService + Binder + 通知 + MediaSession | 2-3 天 |
| LongVideoActivity 集成 + PiP 衔接 | 1-2 天 |
| ShortVideoActivity 集成 + PiP + ViewPager2 限制 | 2 天 |
| 测试 + 边界场景修复 | 2 天 |
| **总计** | **7-10 天** |
