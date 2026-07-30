# 短视频沉浸模式设计

## 目标
短视频右侧栏新增"沉浸模式"按钮，点击后隐藏所有信息覆盖层（文件名、收藏、评论、页码、进度条、更多按钮），只显示纯视频画面。点击画面任意空白处恢复显示。

## 交互设计
- **入口**：右侧竖排按钮栏新增"沉浸"按钮（收藏上方），图标 ic_immersive（眼睛/全屏类图标）
- **进入沉浸**：点击沉浸按钮 → 隐藏所有子控件（tv_title / play_like / play_comment / layout_comment / tv_page / play_seekbar / iv_more / btn_immersive 自身），仅保留纯视频画面
- **退出沉浸**：沉浸模式下点击画面任意位置 → 恢复显示所有信息
- **不影响现有手势**：
  - 非沉浸下：单击=播放/暂停，双击=点赞，长按=设置/速度
  - 沉浸下：单击=退出沉浸（不触发播放/暂停），双击/长按不响应
- **翻页重置**：切换到下一个视频时自动退出沉浸模式

## 实现

### 涉及文件
- `layout_short_video_control_view.xml`：新增沉浸按钮 `btn_immersive`
- `ShortVideoControlView.java`：沉浸状态机 + 交互逻辑

### ShortVideoControlView 改动

#### 新增状态字段
```java
private boolean immersiveMode = false;
```

#### 新增沉浸按钮点击
在 `initView` / listener 绑定处：
```java
binding.btnImmersive.setOnClickListener(v -> {
    setImmersiveMode(true);
});
```

#### setImmersiveMode 方法
```java
private void setImmersiveMode(boolean immersive) {
    this.immersiveMode = immersive;
    // 隐藏/显示所有信息控件
    int visibility = immersive ? GONE : VISIBLE;
    tvTitle.setVisibility(showTitle ? visibility : GONE);
    playLike.setVisibility(showLike ? visibility : GONE);
    playComment.setVisibility(showComment ? visibility : GONE);
    layoutComment.setVisibility(showComment ? visibility : GONE);
    tvPage.setVisibility(showPager ? visibility : GONE);
    playSeekbar.setVisibility(showSeekbar ? visibility : GONE);
    ivMore.setVisibility(showMore ? visibility : GONE);
    btnImmersive.setVisibility(immersive ? GONE : VISIBLE);
    // play_btn（暂停图标）在沉浸下也隐藏
    playBtn.setVisibility(immersive ? GONE : playBtn.getVisibility());
}
```

#### onSingleTapConfirmed 改动
```java
@Override
public boolean onSingleTapConfirmed(MotionEvent e) {
    if (immersiveMode) {
        // 沉浸下单击=退出沉浸，不触发播放/暂停
        setImmersiveMode(false);
        return true;
    }
    // 原有逻辑：切换播放/暂停
    if (!mControlWrapper.isFullScreen()) {
        mControlWrapper.togglePlay();
    }
    return true;
}
```

#### onDoubleTap 改动
```java
@Override
public boolean onDoubleTap(MotionEvent e) {
    if (immersiveMode) return true; // 沉浸下不响应双击
    // 原有点赞逻辑
    ...
}
```

#### onLongPress 改动
在方法开头加：
```java
if (immersiveMode) return;
```

#### 翻页重置
Activity 切换视频时（startPlay / onPageSelected），调用当前 ControlView 的 `setImmersiveMode(false)` 退出沉浸。或在 ControlView 的 `onPlayStateChanged` 收到新状态时重置。

### 布局改动

在 `layout_short_video_control_view.xml` 的右侧竖排按钮栏（ConstraintLayout 里的右侧链）最上方加：
```xml
<ImageView
    android:id="@+id/btn_immersive"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:src="@drawable/ic_immersive"
    android:tint="#FFFFFF"
    android:background="?android:attr/selectableItemBackgroundBorderless"
    app:layout_constraintBottom_toTopOf="@id/play_like"
    app:layout_constraintEnd_toEndOf="parent" />
```

需要创建 `ic_immersive.xml` drawable（一个简洁的"全屏/无边框"图标）。

## 不影响
- 现有播放/暂停、双击点赞、长按设置/速度等手势
- 全屏模式（PLAYER_FULL_SCREEN，由 VideoFullControlView 接管）
- ViewPager2 翻页、缩放手势
