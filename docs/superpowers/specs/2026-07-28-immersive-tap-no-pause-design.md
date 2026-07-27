# 短视频沉浸态单击行为修复设计

## 背景

短视频沉浸体验（`showSysBar` 设置开启时，3 秒无操作自动隐藏覆盖层与系统栏）存在一个回归 bug：

**当前行为**——沉浸态下单击屏幕，会**同时**做两件事：
1. 退出沉浸、恢复覆盖层、重新计时 3 秒（Activity 侧 `onSingleTap()` → `exitAndRescheduleImmersive()`）
2. 调用 `mControlWrapper.togglePlay()` 暂停视频（`ShortVideoControlView.onSingleTapConfirmed`）

根因在 `ShortVideoControlView.java:204-213` 的 `onSingleTapConfirmed`：单击路径**没有** `immersiveMode` 守卫，而同类的 `onDoubleTap`（261 行）和 `onLongPress`（228 行）都有。

**期望行为**——沉浸态单击只退沉浸、继续播放、3 秒后重回沉浸；暂停/播放只在非沉浸态触发。

> 历史注记：初始沉浸模式设计文档（`2026-07-23-short-video-immersive-design.md` 第 12、59-63 行）原本就规定「沉浸下单击=退出沉浸，不触发播放/暂停」。后续提交 `13e2780`（沉浸体验重构为 Activity 级控制 + 3 秒计时器）在重构过程中丢失了 `onSingleTapConfirmed` 的守卫，导致回归。本设计既修复回归，又补充暂停态计时保护。

## 目标

沉浸开关（`spUtil.showSysBar.not()`）开启时：
1. **沉浸中单击** → 退沉浸 + 继续播放 + 重新计时 3 秒（不暂停）
2. **沉浸中双击** → 退沉浸 + 点赞 + 继续播放 + 重新计时 3 秒
3. **非沉浸时单击** → 暂停/播放（保持现状）
4. **非沉浸时双击** → 点赞（保持现状）
5. **暂停态不自动进沉浸**；恢复播放后重新计时

沉浸开关关闭时：无沉浸态可言（不自动进沉浸），单击/双击行为完全不变。

## 非目标

- 不改动长按（速度/更多）手势——沉浸态长按继续被拦截（保持现状）。
- 不改动全屏模式（`PLAYER_FULL_SCREEN`）下的任何手势。
- 不改动翻页（ViewPager2）时的沉浸重置逻辑。
- 不改动沉浸开关本身的 UI/存储。

## 方案选择

评估了三种方案，选定 **方案 A**：

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. 单点加守卫**（选定） | 在 `onSingleTapConfirmed`/`onDoubleTap` 加 `immersiveMode` 守卫，与现有 `onLongPress` 风格对称；计时保护挂在 Activity 已有的 `onPlayStateChanged` 上 | 改动最小、风格统一、零新增接口 |
| B. 统一手势分发器 | 在所有手势回调前加统一的沉浸拦截层 | 语义干净，但改动面大、回归风险高 |
| C. 单击永不暂停 | 单击只退沉浸/显示控制层，暂停只能靠中央按钮 | 违背「非沉浸态单击暂停/播放保持现状」的需求，否决 |

## 涉及文件

| 文件 | 改动 |
|---|---|
| `dy-player/.../view/ShortVideoControlView.java` | `onSingleTapConfirmed`（204-213）、`onDoubleTap`（259-271）|
| `dy-player/.../ui/short_video/ShortVideoActivity.kt` | `scheduleImmersive`（160-166）、`enterImmersiveRunnable`（152-156）、`onPlayStateChanged`（477-515）|

**零新增接口、零新增字段、零布局改动。**

## 详细设计

### 改动 1：沉浸态单击——只退沉浸，不暂停

`ShortVideoControlView.java` 的 `onSingleTapConfirmed`（204-213）开头加 `immersiveMode` 守卫：

```java
@Override
public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
    if (immersiveMode) {
        // 沉浸态单击：仅退出沉浸并重新计时，不暂停播放
        if (mOnShortVideoListener != null) {
            mOnShortVideoListener.onSingleTap();
        }
        return true;
    }
    if (mOnShortVideoListener != null) {
        mOnShortVideoListener.onSingleTap();
    }
    if (mControlWrapper != null && !mControlWrapper.isFullScreen()) {
        mControlWrapper.togglePlay();
    }
    return true;
}
```

- 沉浸态提前 `return`，跳过 `togglePlay()`。
- `onSingleTap()` 的 Activity 实现已是 `exitAndRescheduleImmersive()`，无需改动 Activity。
- `exitImmersive()` 幂等，重复调用无害。

### 改动 2：沉浸态双击——退沉浸 + 点赞 + 继续播放

`ShortVideoControlView.java` 的 `onDoubleTap`（259-271）。两步操作：**(1) 删除第 261 行的 `if (immersiveMode) return true;`**（让点赞逻辑在沉浸态也能跑）；**(2) 在 `return true` 前追加沉浸态退沉浸通知**：

```java
@Override
public boolean onDoubleTap(@NonNull MotionEvent e) {
    // 第 261 行原 `if (immersiveMode) return true;` 已删除
    Log.d(this, "onDoubleTap");
    showLikeAnimation(e.getX(), e.getY());
    if (PlayerInitializer.Player.INSTANCE.getShortShowLike() && !mBinding.playLike.isSelected()) {
        mBinding.playLike.setSelected(true);
        if (mOnShortVideoListener != null) {
            mOnShortVideoListener.onLike(true);
        }
    }
    // 沉浸态双击：退沉浸 + 重新计时（点赞动画照常显示，继续播放）
    if (immersiveMode && mOnShortVideoListener != null) {
        mOnShortVideoListener.onSingleTap();
    }
    return true;
}
```

- **删除**第 261 行的 `if (immersiveMode) return true;`，让点赞逻辑正常执行。
- 沉浸态额外调一次 `onSingleTap()` 通知 Activity 退沉浸。原因：`GestureDetector` 判定双击后**不会**回调 `onSingleTapConfirmed`，Activity 拿不到退沉浸信号，必须在此补发。
- 复用 `onSingleTap()` 而非新增方法：语义一致（「请求退沉浸并重新计时」），且 `exitImmersive()` 幂等。

### 改动 3：暂停态不自动进沉浸

Activity 层三道防线，全部复用已有设施，判据用 `mVideoView.isPlaying`（`BaseVideoView.isPlaying`，权威来源）：

**(a) 调度时拦截**——`ShortVideoActivity.scheduleImmersive()`（160-166）：

```kotlin
private fun scheduleImmersive() {
    if (!spUtil.showSysBar.not()) return
    immersiveHandler.removeCallbacks(enterImmersiveRunnable)
    if (::mVideoView.isInitialized && !mVideoView.isPlaying) return   // 暂停态不调度
    immersiveScheduled = true
    immersiveHandler.postDelayed(enterImmersiveRunnable, 3000)
}
```

**(b) 到点执行时再确认**——`enterImmersiveRunnable`（152-156）：

```kotlin
private val enterImmersiveRunnable = Runnable {
    immersiveScheduled = false
    if (::mVideoView.isInitialized && mVideoView.isPlaying) {
        activeShortVideoControlView?.enterImmersive()
    }
}
```

**(c) 播放状态联动**——复用 Activity 已有的 `mVideoView.addOnStateChangeListener` 的 `onPlayStateChanged`（477-515），新增 `STATE_PAUSED` 分支并在 `STATE_PLAYING` 分支补 `scheduleImmersive()`：

```kotlin
override fun onPlayStateChanged(playState: Int) {
    if (playState == VideoView.STATE_PREPARED) { ... }
    if (playState == VideoView.STATE_PLAYING) {
        ...
        onPlayStart()
        scheduleMediaLastPlayedUpdate(mCurPos)
        scheduleImmersive()    // 恢复播放 → 重新计时 3 秒
    }
    if (playState == VideoView.STATE_PAUSED) {
        cancelImmersive()      // 暂停 → 取消已挂的计时
    }
    if (playState == VideoView.STATE_ERROR) { ... }
}
```

三道防线：暂停瞬间 `cancelImmersive()` 清计时；漏网的调度被 `scheduleImmersive()` 里 `!isPlaying` 拦；到点执行 `enterImmersiveRunnable` 里 `isPlaying` 再兜底。

> 注：`STATE_PLAYING` 分支新增 `scheduleImmersive()` 与 `startPlay()`（733-736）已有的调度不冲突——`scheduleImmersive()` 内部先 `removeCallbacks` 再 `postDelayed`，幂等。

## 测试

按 `superpowers:test-driven-development` 流程：先写失败用例，再改实现使其通过。

### 单元测试（纯逻辑，可在 JVM 跑）

针对 `ShortVideoControlView` 的手势分发逻辑。由于 `onSingleTapConfirmed`/`onDoubleTap` 依赖 `MotionEvent` 和 `ControlWrapper`，需通过 `OnShortVideoListener` mock + `ControlWrapper` mock 验证副作用：

- `沉浸态 onSingleTapConfirmed → 只调 onSingleTap，不调 togglePlay`
- `非沉浸态 onSingleTapConfirmed → 调 onSingleTap 且调 togglePlay`
- `沉浸态 onDoubleTap → 调 onLike(如配置) 且调 onSingleTap（退沉浸），不调 togglePlay`
- `非沉浸态 onDoubleTap → 调 onLike，不调 onSingleTap，不调 togglePlay`

Activity 的 `scheduleImmersive`/`enterImmersiveRunnable` 依赖 `Handler` 与 `VideoView`，属集成层，倾向用 instrumented/手动 UI 验证（见下）。

### UI 回归验证（手动 + 既有 `scripts/verify-ui.sh` 风格）

沉浸开关开启时：
1. 进入沉浸（等待 3 秒）→ 单击 → 覆盖层恢复、**视频继续播放**、3 秒后重回沉浸 ✅
2. 进入沉浸 → 双击 → 点赞动画弹出、覆盖层恢复、**视频继续播放**、3 秒后重回沉浸 ✅
3. 非沉浸态 → 单击 → 暂停 ✅（回归无破坏）
4. 非沉浸态 → 双击 → 点赞 ✅（回归无破坏）
5. 进入沉浸 → 单击退沉浸 → 再单击暂停 → 等待 >3 秒 → **不进沉浸** ✅
6. 暂停态 → 恢复播放 → 等待 3 秒 → 自动进沉浸 ✅

沉浸开关关闭时：
7. 单击/双击行为与改动前完全一致 ✅

## 风险

- **`immersiveMode` 是 ControlView 实例字段**，但 `globalImmersiveMode` 是静态字段。改动只读实例字段 `immersiveMode`，与 `onLongPress`/`onDoubleTap` 现有守卫一致，无新风险。
- **`onPlayStateChanged` 的 `STATE_PLAYING` 新增 `scheduleImmersive()`** 可能在某些自动重播路径上多调一次，但 `scheduleImmersive()` 幂等（先 remove 再 post），无害。
- 暂停态三道防线均基于 `mVideoView.isPlaying`。极端边界（`isPlaying` 在状态转换瞬间短暂失真）已被「调度时 + 执行时」双重检查 + 暂停即 `cancelImmersive()` 覆盖。
