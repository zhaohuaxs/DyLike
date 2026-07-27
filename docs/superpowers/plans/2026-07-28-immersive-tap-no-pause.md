# 短视频沉浸态单击行为修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复沉浸态下单击屏幕会暂停视频的回归——改为沉浸态单击/双击只退沉浸、继续播放，并保证暂停态不自动进沉浸。

**Architecture:** 单点守卫修复。在 `ShortVideoControlView` 的 `onSingleTapConfirmed`/`onDoubleTap` 加 `immersiveMode` 守卫（与已有 `onLongPress` 对称）；在 `ShortVideoActivity` 的 `scheduleImmersive`/`enterImmersiveRunnable` 加 `mVideoView.isPlaying` 拦截，并在已有的 `onPlayStateChanged` 监听里联动 `STATE_PLAYING`→重新计时、`STATE_PAUSED`→取消计时。零新增接口、零新增字段、零布局改动。

**Tech Stack:** Kotlin / Java（Android）、dkplayer（`ControlWrapper`/`BaseVideoController`）、adb + logcat（UI 回归验证）。

## Global Constraints

- **测试策略：仅 UI 脚本验证**（用户已定）。本计划**不写**单元测试，**不**新增 Robolectric/mock 基建，**不**抽取 helper。仓库现有测试基建（无 Robolectric、mockk 声明但未用、View 需 Context 无法 JVM 实例化）无法支撑 View/Activity 单测，故全部通过扩展 `scripts/verify-ui.sh` 用 adb + logcat 验证。
- **播放状态判据统一用 `mVideoView.isPlaying`**（`BaseVideoView.java:578`），它是权威来源，Activity 已在第 997 行使用。
- **沉浸开关字段为 `spUtil.showSysBar`**，默认 `true`（`SpUtil.kt:144`）。沉浸**开启**= `spUtil.showSysBar.not()`。注意语义反转：`showSysBar=true` 表示「显示系统栏」即沉浸**关闭**。
- **`immersiveMode` 是 `ShortVideoControlView` 实例字段**（第 66 行），`enterImmersive()`（519）置 true、`exitImmersive()`（536）置 false。`exitImmersive()` 幂等。
- **`OnShortVideoListener.onSingleTap()` 的 Activity 实现已是 `exitAndRescheduleImmersive()`**（`ShortVideoActivity.kt:622-625`），改动复用它，不新增接口。
- **代码风格**：Java 文件用 `@NonNull` 注解、4 空格缩进、中文注释（与 `ShortVideoControlView.java` 现有一致）；Kotlin 文件保持现有风格。

---

## File Structure

| 文件 | 责任 | 改动类型 |
|---|---|---|
| `dy-player/src/main/java/me/lingci/dy/player/view/ShortVideoControlView.java` | 手势分发：单击/双击在沉浸态的语义 | 修改 2 处方法 |
| `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt` | 沉浸计时器：调度/执行的播放态守卫 + 播放状态联动 | 修改 3 处 |
| `scripts/verify-ui.sh` | UI 回归验证：沉浸态单击/双击后播放不中断、暂停态不进沉浸 | 追加 3 个测试段 |

---

## Task 1：沉浸态单击——只退沉浸，不暂停

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/view/ShortVideoControlView.java:204-213`（`onSingleTapConfirmed`）

**Interfaces:**
- Consumes: 实例字段 `immersiveMode`（boolean）、`mOnShortVideoListener`（`OnShortVideoListener`）、`mControlWrapper`（`ControlWrapper`）
- Produces: 无新接口。本任务完成后，沉浸态单击不再调用 `mControlWrapper.togglePlay()`。

**背景**：当前 `onSingleTapConfirmed`（204-213）无 `immersiveMode` 守卫，每次单击都调 `togglePlay()`。同类 `onLongPress`（228）、`onDoubleTap`（261）都有守卫，唯独单击漏了。

- [ ] **Step 1：阅读当前实现，确认行号**

打开 `dy-player/src/main/java/me/lingci/dy/player/view/ShortVideoControlView.java`，确认 204-213 行为：

```java
    // 解决单击：仅当确定不是双击时触发
    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        if (mOnShortVideoListener != null) {
            mOnShortVideoListener.onSingleTap();
        }
        if (mControlWrapper != null && !mControlWrapper.isFullScreen()) {
            mControlWrapper.togglePlay();
        }
        return true;
    }
```

- [ ] **Step 2：修改 `onSingleTapConfirmed`，沉浸态提前 return**

将 204-213 行整段替换为：

```java
    // 解决单击：仅当确定不是双击时触发
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

要点：沉浸态提前 `return true`，跳过 `togglePlay()`。`onSingleTap()` 由 Activity 的 `exitAndRescheduleImmersive()` 处理（退沉浸 + 重新计时 3 秒）。

- [ ] **Step 3：编译验证**

Run: `./gradlew :dy-player:compileBetaDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL（仅 Java 改动，无需 Kotlin 编译）。

- [ ] **Step 4：提交**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/view/ShortVideoControlView.java
git commit -m "fix(short-video): 沉浸态单击只退沉浸不暂停播放"
```

---

## Task 2：沉浸态双击——退沉浸 + 点赞 + 继续播放

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/view/ShortVideoControlView.java:259-271`（`onDoubleTap`）

**Interfaces:**
- Consumes: `immersiveMode`、`mOnShortVideoListener`、`mBinding.playLike`、`PlayerInitializer.Player.INSTANCE.getShortShowLike()`
- Produces: 无新接口。沉浸态双击不再被拦截，会触发点赞 + 退沉浸通知。

**背景**：当前 `onDoubleTap`（259-271）第 261 行 `if (immersiveMode) return true;` 直接拦截沉浸态双击。改动需删除该拦截，并在 `return true` 前补发 `onSingleTap()`（因 `GestureDetector` 判定双击后不会回调 `onSingleTapConfirmed`，Activity 拿不到退沉浸信号）。

- [ ] **Step 1：阅读当前实现，确认行号**

确认 259-271 行为：

```java
    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
        if (immersiveMode) return true;
        Log.d(this, "onDoubleTap");
        showLikeAnimation(e.getX(), e.getY());
        if (PlayerInitializer.Player.INSTANCE.getShortShowLike() && !mBinding.playLike.isSelected()) {
            mBinding.playLike.setSelected(true);
            if (mOnShortVideoListener != null) {
                mOnShortVideoListener.onLike(true);
            }
        }
        return true;
    }
```

- [ ] **Step 2：替换 `onDoubleTap` 整段**

将 259-271 行整段替换为（删除第 261 行的 `if (immersiveMode) return true;`，在 `return true` 前追加沉浸态退沉浸通知）：

```java
    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
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

要点：
1. **删除**原第 261 行 `if (immersiveMode) return true;`，让点赞逻辑在沉浸态正常执行。
2. 末尾追加 `if (immersiveMode && mOnShortVideoListener != null)` 分支调 `onSingleTap()` 通知 Activity 退沉浸。
3. 双击不会触发 `togglePlay()`（`onDoubleTap` 本就不调它），故「继续播放」是自然结果，无需额外代码。

- [ ] **Step 3：编译验证**

Run: `./gradlew :dy-player:compileBetaDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4：提交**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/view/ShortVideoControlView.java
git commit -m "fix(short-video): 沉浸态双击退沉浸+点赞，继续播放"
```

---

## Task 3：暂停态不自动进沉浸——调度与执行守卫

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt:152-166`（`enterImmersiveRunnable` + `scheduleImmersive`）

**Interfaces:**
- Consumes: 实例字段 `mVideoView`（`VideoView`，可能未初始化故用 `::mVideoView.isInitialized`）、`spUtil.showSysBar`、`activeShortVideoControlView`、`immersiveHandler`、`enterImmersiveRunnable`、`immersiveScheduled`
- Produces: 无新接口。`scheduleImmersive()` 与 `enterImmersiveRunnable` 在暂停态各自短路返回。

**背景**：当前 `scheduleImmersive()`（160-166）与 `enterImmersiveRunnable`（152-156）均无播放态判断。本任务加「调度时 + 执行时」两道 `mVideoView.isPlaying` 守卫。Task 4 再加「播放状态联动」第三道。

- [ ] **Step 1：阅读当前实现，确认行号**

确认 150-166 行为：

```kotlin
    private var activeShortVideoControlView: ShortVideoControlView? = null

    /** 沉浸体验定时器(Activity 级,确保操作的是活跃的 ControlView)。 */
    private val immersiveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var immersiveScheduled = false
    private val enterImmersiveRunnable = Runnable {
        immersiveScheduled = false
        activeShortVideoControlView?.enterImmersive()
    }
    private fun scheduleImmersive() {
        if (!spUtil.showSysBar.not()) return
        immersiveHandler.removeCallbacks(enterImmersiveRunnable)
        immersiveScheduled = true
        immersiveHandler.postDelayed(enterImmersiveRunnable, 3000)
    }
```

- [ ] **Step 2：修改 `enterImmersiveRunnable`，执行前确认播放中**

将

```kotlin
    private val enterImmersiveRunnable = Runnable {
        immersiveScheduled = false
        activeShortVideoControlView?.enterImmersive()
    }
```

替换为：

```kotlin
    private val enterImmersiveRunnable = Runnable {
        immersiveScheduled = false
        // 暂停态不自动进沉浸(用户主动暂停,需保留控制层);恢复播放后由 onPlayStateChanged 重新计时
        if (::mVideoView.isInitialized && mVideoView.isPlaying) {
            activeShortVideoControlView?.enterImmersive()
        }
    }
```

- [ ] **Step 3：修改 `scheduleImmersive`，暂停态不调度**

将

```kotlin
    private fun scheduleImmersive() {
        if (!spUtil.showSysBar.not()) return
        immersiveHandler.removeCallbacks(enterImmersiveRunnable)
        immersiveScheduled = true
        immersiveHandler.postDelayed(enterImmersiveRunnable, 3000)
    }
```

替换为：

```kotlin
    private fun scheduleImmersive() {
        if (!spUtil.showSysBar.not()) return
        immersiveHandler.removeCallbacks(enterImmersiveRunnable)
        // 视频暂停时不调度进沉浸(用户主动暂停,需保留控制层)
        if (::mVideoView.isInitialized && !mVideoView.isPlaying) return
        immersiveScheduled = true
        immersiveHandler.postDelayed(enterImmersiveRunnable, 3000)
    }
```

- [ ] **Step 4：编译验证**

Run: `./gradlew :dy-player:compileBetaDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5：提交**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
git commit -m "fix(short-video): 暂停态不自动进沉浸(调度+执行双守卫)"
```

---

## Task 4：播放状态联动——恢复播放重计时，暂停取消计时

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt:477-515`（`onPlayStateChanged` 回调）

**Interfaces:**
- Consumes: Task 3 产出的 `scheduleImmersive()` / `cancelImmersive()`（后者已存在于原代码，`ShortVideoActivity.kt:172-175`）
- Produces: 无新接口。`onPlayStateChanged` 在 `STATE_PLAYING` 调 `scheduleImmersive()`、在 `STATE_PAUSED` 调 `cancelImmersive()`。

**背景**：当前 `onPlayStateChanged`（477-515）只处理 `STATE_PREPARED`/`STATE_PLAYING`/`STATE_ERROR`，**无 `STATE_PAUSED` 分支**，`STATE_PLAYING` 分支也未联动沉浸计时。本任务补这两处，形成暂停态第三道防线。

- [ ] **Step 1：阅读当前 `onPlayStateChanged`，确认行号**

确认 477-488 行附近为（关注 `STATE_PLAYING` 分支结尾）：

```kotlin
            override fun onPlayStateChanged(playState: Int) {
                if (playState == VideoView.STATE_PREPARED) {
                    logAndCache(TAG, "D", "STATE_PREPARED: pos=$mCurPos")
                    updateSubtitleDocking()
                    attachSubtitle()
                }
                if (playState == VideoView.STATE_PLAYING) {
                    logAndCache(TAG, "D", "STATE_PLAYING: pos=$mCurPos")
                    updateSubtitleDocking()
                    onPlayStart()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                }
                if (playState == VideoView.STATE_ERROR) {
```

- [ ] **Step 2：在 `STATE_PLAYING` 分支补 `scheduleImmersive()`**

将

```kotlin
                if (playState == VideoView.STATE_PLAYING) {
                    logAndCache(TAG, "D", "STATE_PLAYING: pos=$mCurPos")
                    updateSubtitleDocking()
                    onPlayStart()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                }
```

替换为：

```kotlin
                if (playState == VideoView.STATE_PLAYING) {
                    logAndCache(TAG, "D", "STATE_PLAYING: pos=$mCurPos")
                    updateSubtitleDocking()
                    onPlayStart()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                    // 恢复播放 → 重新计时沉浸(scheduleImmersive 内部先 remove 再 post,幂等)
                    scheduleImmersive()
                }
```

- [ ] **Step 3：新增 `STATE_PAUSED` 分支**

在 `STATE_PLAYING` 的 `}` 之后、`if (playState == VideoView.STATE_ERROR)` 之前，插入：

```kotlin
                if (playState == VideoView.STATE_PAUSED) {
                    logAndCache(TAG, "D", "STATE_PAUSED: pos=$mCurPos")
                    // 暂停 → 取消已挂的沉浸计时(用户主动暂停,不应再被吞掉控制层)
                    cancelImmersive()
                }
```

替换后该区域应为：

```kotlin
                if (playState == VideoView.STATE_PLAYING) {
                    logAndCache(TAG, "D", "STATE_PLAYING: pos=$mCurPos")
                    updateSubtitleDocking()
                    onPlayStart()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                    scheduleImmersive()
                }
                if (playState == VideoView.STATE_PAUSED) {
                    logAndCache(TAG, "D", "STATE_PAUSED: pos=$mCurPos")
                    cancelImmersive()
                }
                if (playState == VideoView.STATE_ERROR) {
```

要点：`STATE_PAUSED` 日志用 `logAndCache(TAG, "D", ...)` 与现有风格一致（`STATE_PAUSED` 不是错误，用 D 级）。`cancelImmersive()` 已定义于原代码 172-175 行，直接复用。

- [ ] **Step 4：确认 `cancelImmersive` 存在且签名匹配**

Run: `grep -n "private fun cancelImmersive" dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt`
Expected: 输出形如 `172:    private fun cancelImmersive() {`（确认无参、private、存在）。若不存在则说明行号有偏移，按实际位置引用即可，逻辑不变。

- [ ] **Step 5：编译验证**

Run: `./gradlew :dy-player:compileBetaDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6：提交**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
git commit -m "fix(short-video): 播放状态联动沉浸计时(播放重计时/暂停取消)"
```

---

## Task 5：扩展 verify-ui.sh——沉浸态单击/双击后播放不中断

**Files:**
- Modify: `scripts/verify-ui.sh`（在文件末尾追加测试段，复用脚本顶部已定义的 `ADB`/`PKG`/`SHORT_VIDEO_ACT`/`TAP_X`/`TAP_Y`/`WAIT_MED` 等变量）

**Interfaces:**
- Consumes: Task 1-4 的行为变更；脚本顶部变量 `ADB`、`PKG`、`WAIT_MED`、`SHORT_VIDEO_ACT`；`find_test_video` 函数；`clear_logcat` 函数；`log_pass`/`log_fail`/`log_info` 函数。
- Produces: 三个新的 UI 回归断言（沉浸态单击后播放态=PLAYING；沉浸态双击后播放态=PLAYING；暂停态 3 秒后 UI 仍可见）。

**可观测性原理**：播放状态通过 logcat 验证。`onPlayStateChanged` 在 `STATE_PLAYING`/`STATE_PAUSED` 都会 `logAndCache(TAG,"D",...)` → `Log.d(TAG, message)`（见 `PlaybackTraceHelper.kt:17`），故 `adb logcat -d | grep "STATE_PLAYING\|STATE_PAUSED"` 可读出最近状态。TAG 为 `ShortVideoActivity`（Kotlin 默认 companion TAG）。

- [ ] **Step 1：定位脚本末尾追加位置**

Run: `tail -5 scripts/verify-ui.sh`
确认末尾结构（通常是某个测试段的 `fi` 结束）。新测试段追加在文件最末尾。

- [ ] **Step 2：追加「沉浸态单击后播放不中断」测试**

在 `scripts/verify-ui.sh` 末尾追加。**注意变量作用域**：脚本里 `TAP_X`/`TAP_Y`/`SCREEN_W`/`SCREEN_H` 仅在测试 11（346-349 行）局部定义，`SHORT_VIDEO_ACT` 仅在测试 10（307 行）定义。为保证本测试段可独立运行，开头先（重新）计算屏幕中心坐标与 Activity 全名：

```bash

# ============================================================
# 测试 13: 沉浸态单击 — 退沉浸但继续播放(不暂停)
# ============================================================
echo ""
log_info "=== 测试 13: 沉浸态单击应退沉浸且继续播放 ==="

# 计算屏幕中心(避免依赖测试 11 的局部变量)
SCREEN_W=$($ADB shell wm size 2>/dev/null | grep -o '[0-9]*x[0-9]*' | head -1 | cut -d'x' -f1)
SCREEN_H=$($ADB shell wm size 2>/dev/null | grep -o '[0-9]*x[0-9]*' | head -1 | cut -d'x' -f2)
TAP_X=$((SCREEN_W / 2))
TAP_Y=$((SCREEN_H / 2))
SHORT_VIDEO_ACT="$PKG/me.lingci.dy.player.ui.short_video.ShortVideoActivity"

# 确保沉浸开启(showSysBar=false),幂等
$ADB shell 'run-as '"$PKG"' sed -i '"'"'s|<boolean name="showSysBar" value="true" />|<boolean name="showSysBar" value="false" />|'"'"' /data/data/'"$PKG"'/shared_prefs/'"$PKG"'_preferences.xml' 2>/dev/null || true

$ADB shell am force-stop $PKG
sleep 2
SHORT_VIDEO=$(find_test_video)
clear_logcat
$ADB shell am start -a android.intent.action.VIEW -d "file://$SHORT_VIDEO" -t "video/mp4" -n "$SHORT_VIDEO_ACT" >/dev/null 2>&1
sleep $WAIT_MED

# 等待进入沉浸(3秒定时器 + 余量)
sleep 5

# 记录单击前的最近播放状态
BEFORE=$( $ADB logcat -d 2>/dev/null | grep -oE 'STATE_(PLAYING|PAUSED)' | tail -1 )
log_info "单击前最近状态: $BEFORE"

# 沉浸态单击屏幕中心
$ADB shell input tap $TAP_X $TAP_Y
sleep 1

# 单击后 1 秒内的播放状态(应仍为 PLAYING, 不应出现新的 STATE_PAUSED)
AFTER=$( $ADB logcat -d 2>/dev/null | grep -oE 'STATE_(PLAYING|PAUSED)' | tail -1 )
log_info "单击后最近状态: $AFTER"

if [ "$AFTER" = "STATE_PLAYING" ]; then
    log_pass "沉浸态单击后继续播放(未暂停)"
else
    log_fail "沉浸态单击后播放被中断(状态=$AFTER, 期望 STATE_PLAYING)"
fi
```

要点：用 `grep -oE 'STATE_(PLAYING|PAUSED)' | tail -1` 取最近一条状态。期望 `AFTER=STATE_PLAYING`。若单击误触发 `togglePlay()`，会出现 `STATE_PAUSED`。

- [ ] **Step 3：追加「沉浸态双击后播放不中断 + UI 恢复」测试**

紧接上文追加：

```bash

# ============================================================
# 测试 14: 沉浸态双击 — 退沉浸 + 点赞 + 继续播放
# ============================================================
echo ""
log_info "=== 测试 14: 沉浸态双击应退沉浸且继续播放 ==="

# 重新进入沉浸
$ADB shell am force-stop $PKG
sleep 2
clear_logcat
$ADB shell am start -a android.intent.action.VIEW -d "file://$SHORT_VIDEO" -t "video/mp4" -n "$SHORT_VIDEO_ACT" >/dev/null 2>&1
sleep $WAIT_MED
sleep 5  # 进沉浸

# 双击(两次 input tap,间隔 < 双击阈值 300ms)
$ADB shell input tap $TAP_X $TAP_Y
$ADB shell input tap $TAP_X $TAP_Y
sleep 1

AFTER2=$( $ADB logcat -d 2>/dev/null | grep -oE 'STATE_(PLAYING|PAUSED)' | tail -1 )
log_info "双击后最近状态: $AFTER2"

# UI 应恢复(tv_title 可见)
$ADB shell uiautomator dump /sdcard/dbl_check.xml >/dev/null 2>&1
$ADB exec-out cat /sdcard/dbl_check.xml > /tmp/dbl_ui.xml 2>/dev/null
DBL_TITLE=$(grep -c 'tv_title' /tmp/dbl_ui.xml 2>/dev/null || echo "0")

if [ "$AFTER2" = "STATE_PLAYING" ] && [ "$DBL_TITLE" -gt 0 ]; then
    log_pass "沉浸态双击后退沉浸并继续播放"
else
    log_fail "沉浸态双击异常(状态=$AFTER2, tv_title出现次数=$DBL_TITLE)"
fi
```

要点：双击用两次 `input tap` 模拟（adb 无原生 doubletap）。期望 `AFTER2=STATE_PLAYING` 且 `tv_title` 可见（说明退沉浸）。注：`getShortShowLike` 默认值若为 false 则点赞标记不变，但退沉浸与播放态仍可验证。

- [ ] **Step 4：追加「暂停态不自动进沉浸」测试**

紧接上文追加：

```bash

# ============================================================
# 测试 15: 暂停态不自动进沉浸 — 暂停后等待 >3s 控制层仍可见
# ============================================================
echo ""
log_info "=== 测试 15: 暂停态不应自动进沉浸 ==="

# 当前应在非沉浸态(上一测试退出了沉浸)。单击一次触发暂停(非沉浸态单击=暂停)
# 注意:需确保此刻是非沉浸态。先等一秒再单击,此时 UI 已恢复(测试14已退出沉浸)
$ADB shell input tap $TAP_X $TAP_Y
sleep 1

# 确认已暂停
PAUSED_STATE=$( $ADB logcat -d 2>/dev/null | grep -oE 'STATE_(PLAYING|PAUSED)' | tail -1 )
log_info "暂停操作后状态: $PAUSED_STATE"

# 等待超过沉浸定时器(3秒 + 余量)
sleep 5

# UI 应仍可见(tv_title 存在),证明未进沉浸
$ADB shell uiautomator dump /sdcard/pause_check.xml >/dev/null 2>&1
$ADB exec-out cat /sdcard/pause_check.xml > /tmp/pause_ui.xml 2>/dev/null
PAUSE_TITLE=$(grep -c 'tv_title' /tmp/pause_ui.xml 2>/dev/null || echo "0")

if [ "$PAUSE_TITLE" -gt 0 ]; then
    log_pass "暂停态未自动进沉浸(控制层保留)"
else
    log_fail "暂停态错误地进入了沉浸(tv_title 被隐藏)"
fi

# 恢复播放(再单击一次),避免影响后续测试
$ADB shell input tap $TAP_X $TAP_Y
sleep 1
```

要点：非沉浸态单击=暂停（Task 1 的改动只影响沉浸态，非沉浸态单击仍 togglePlay）。等待 5 秒后 `tv_title` 应仍可见，证明暂停态未进沉浸。若 Task 3/4 的守卫失效，3 秒后会进沉浸、`tv_title` 消失。

- [ ] **Step 5：语法检查脚本**

Run: `bash -n scripts/verify-ui.sh`
Expected: 无输出（语法正确）。

- [ ] **Step 6：提交**

```bash
git add scripts/verify-ui.sh
git commit -m "test(short-video): 沉浸态单击/双击不暂停 + 暂停态不进沉浸 UI 回归"
```

---

## Task 6：真机/模拟器端到端验证

**Files:** 无（仅运行验证）

**前置条件**：已连接真机（USB 调试）或模拟器，已安装 betaDebug APK（含 Task 1-5 全部改动）。

- [ ] **Step 1：构建并安装 betaDebug**

Run: `./gradlew :dy-player:installBetaDebug`
Expected: BUILD SUCCESSFUL + `Installed on ...`。

- [ ] **Step 2：运行扩展后的 UI 回归脚本**

Run: `./scripts/verify-ui.sh`（若多设备，传 serial：`./scripts/verify-ui.sh <serial>`）
Expected: 测试 13/14/15 全部 `PASS`。若测试 13/14 失败（状态=STATE_PAUSED），说明 Task 1/2 的沉浸态守卫未生效；若测试 15 失败（tv_title 消失），说明 Task 3/4 的暂停态守卫未生效。

- [ ] **Step 3：手动复核沉浸开关关闭时的回归（无沉浸态）**

在 App 设置里关闭沉浸体验（`showSysBar=true`），确认：
- 单击 → 暂停/播放 正常
- 双击 → 点赞 正常
- 等待任意时长 → UI 不自动隐藏（无沉浸态）

Expected: 行为与改动前完全一致。

- [ ] **Step 4：若全部通过，确认工作树干净**

Run: `git status`
Expected: `nothing to commit, working tree clean`（所有改动已在 Task 1-5 提交）。

---

## Self-Review 记录

计划写完后对照 spec 复核（详见执行前自查），结论：spec 的改动 1/2/3 全部覆盖；测试策略按用户决定从「单测+UI」调整为「仅 UI 脚本」，已在 Global Constraints 与 Task 5 说明依据；无占位符；类型/方法名（`scheduleImmersive`/`cancelImmersive`/`onSingleTap`/`immersiveMode`/`mVideoView.isPlaying`）与现有代码一致。
