package me.lingci.dy.player.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.Lists;

import java.util.List;

import me.lingci.dy.player.R;
import me.lingci.dy.player.databinding.LayoutShortVideoControlViewBinding;
import me.lingci.lib.base.util.Log;
import me.lingci.lib.player.danmaku.PlayerInitializer;
import me.lingci.lib.player.widget.videoview.CustomVideoView;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.BaseVideoView;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.L;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ShortVideoControlView extends FrameLayout implements IControlComponent,
        GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener,
        ScaleGestureDetector.OnScaleGestureListener,
        View.OnTouchListener {

    private static final String SUBTITLE_GESTURE_TRACE_TAG = "SubtitleGestureTrace";

    private static final List<Float> SPEED_NODES = Lists.newArrayList(0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 5f);

    public interface OnVideoTransformChangedListener {
        void onVideoTransformChanged();
    }

    private final LayoutShortVideoControlViewBinding mBinding;

    private ControlWrapper mControlWrapper;
    private final int mScaledTouchSlop;

    private boolean mIsDragging = false;
    private final GestureDetector mGestureDetector;
    private boolean mIsLongPressing = false;
    private float mCurrentSpeed = 1.0f;

    /**
     * 自动沉浸体验：开启后播放 3 秒自动隐藏 UI + 系统栏，
     * 点击屏幕退出沉浸，3 秒无操作再次进入沉浸。
     */
    private boolean immersiveMode = false;
    /** 全局沉浸标志:任何 ControlView 进入沉浸时设为 true,所有实例共享。 */
    private static boolean globalImmersiveMode = false;
    private boolean mIsSpeedLocked = false;
    private int mSeekPosition = -1;
    private boolean mFirstTouch;
    private boolean mChangePosition;
    private float mLongPressY; // 记录长按触发时的 Y 坐标
    private boolean mChangeLock;
    private boolean mIsBottomLongPress; // 是否下半部分长按
    private float mLongPressStartX; // 长按触发时的 X 坐标
    private float mLongPressStartSpeed; // 长按开始时的速度

    private final ScaleGestureDetector mScaleGestureDetector;
    private boolean mIsScaling = false;
    private float mCurrentScale = 1.0f;
    private boolean mHasScaled = false;
    private float mDownFocusX, mDownFocusY;
    private float mTranslateBaseX, mTranslateBaseY;
    private long mLastTwoFingerUpTime = 0;
    private static final long DOUBLE_TAP_TIMEOUT = 300;

    public interface OnShortVideoListener {

        void onLike(boolean like);

        void onComment();

        void onMore();

        void onMoreAction();

        void playNext();

        void onChangeSysBar(boolean show);

        void onSingleTap();

    }

    private OnShortVideoListener mOnShortVideoListener;
    private OnVideoTransformChangedListener mOnVideoTransformChangedListener;

    public ShortVideoControlView(@NonNull Context context) {
        super(context);
    }

    public ShortVideoControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ShortVideoControlView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        mGestureDetector = new GestureDetector(getContext(), this);
        mScaleGestureDetector = new ScaleGestureDetector(getContext(), this);
        mBinding = LayoutShortVideoControlViewBinding.inflate(LayoutInflater.from(getContext()), this, true);
        //LayoutInflater.from(getContext()).inflate(R.layout.layout_tiktok_control_view, this, true);
        mScaledTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        mBinding.ivFull.setOnClickListener(v -> {
            if (!mControlWrapper.isPlaying()) {
                mControlWrapper.togglePlay();
            }
            mBinding.playSeekbar.setEnabled(false);
            mControlWrapper.toggleFullScreen(PlayerUtils.scanForActivity(getContext()));
            //mControlWrapper.toggleFullScreen();
        });
        mBinding.playLike.setOnClickListener(v -> {
            v.setScaleX(0.8f);
            v.setScaleY(0.8f);
            v.postDelayed(() -> {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }, 100);
            if (mOnShortVideoListener != null) {
                v.setSelected(!v.isSelected());
                mOnShortVideoListener.onLike(v.isSelected());
            }
        });
        mBinding.playComment.setOnClickListener(v -> {
            if (mOnShortVideoListener != null) {
                mOnShortVideoListener.onComment();
            }
        });
        mBinding.ivMore.setOnClickListener(v -> {
            if (mOnShortVideoListener != null) {
                mOnShortVideoListener.onMoreAction();
            }
        });
        changeVisibility();
        mBinding.speedNode.setVisibility(GONE);
        mBinding.speedNode.setNodes(SPEED_NODES, 2);
        mBinding.tvProgress.setVisibility(GONE);
        mBinding.playSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * progress) / seekBar.getMax();
                String progressText = PlayerUtils.stringForTime((int) newPosition) + " / " + PlayerUtils.stringForTime((int) duration);
                mBinding.tvProgress.setText(progressText);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsDragging = true;
                mBinding.tvProgress.setVisibility(VISIBLE);
                mControlWrapper.stopProgress();
                mControlWrapper.stopFadeOut();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mBinding.tvProgress.setVisibility(GONE);
                long duration = mControlWrapper.getDuration();
                long newPosition = (long) ((double) duration * mBinding.playSeekbar.getProgress() / mBinding.playSeekbar.getMax());
                mControlWrapper.seekTo(newPosition);
                mIsDragging = false;
                mControlWrapper.startProgress();
                mControlWrapper.startFadeOut();
            }
        });
        setOnTouchListener(this);
    }

    public void setOnLikeClickListener(OnShortVideoListener onShortVideoListener) {
        this.mOnShortVideoListener = onShortVideoListener;
    }

    public void setOnVideoTransformChangedListener(OnVideoTransformChangedListener listener) {
        mOnVideoTransformChangedListener = listener;
    }

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

    @Override
    public boolean onDown(@NonNull MotionEvent e) {
        if (PlayerUtils.isEdge(getContext(), e)) {
            return true;
        }
        mFirstTouch = true;
        mChangePosition = false;
        mIsLongPressing = false;
        return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {
        if (immersiveMode || mControlWrapper == null || e == null) return;

        mIsLongPressing = true;
        mLongPressY = e.getY();
        mLongPressStartX = e.getX();
        mLongPressStartSpeed = mCurrentSpeed;
        getParent().requestDisallowInterceptTouchEvent(true);

        int x = (int) e.getX();
        int y = (int) e.getY();
        int width = getWidth();
        int height = getHeight();

        mIsBottomLongPress = y > height * 3 / 5;

        if (mIsBottomLongPress) {
            mBinding.speedNode.setVisibility(VISIBLE);
            mBinding.speedNode.setValue(mCurrentSpeed);
        } else {
            if (x < width / 4 || x > width * 3 / 4) {
                adjustSpeed(x > width / 4);
                showSpeedIndicator();
            } else {
                if (mOnShortVideoListener != null) {
                    mOnShortVideoListener.onMore();
                }
            }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

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

    @Override
    public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
        if (e1 == null || PlayerUtils.isEdge(getContext(), e1)) return false;
        Log.d(this, "onScroll", mFirstTouch, mIsLongPressing);
        if (mFirstTouch) {
            // 横向滚动分量大于纵向，判定为进度调节
            mChangePosition = Math.abs(distanceX) > Math.abs(distanceY);
            mFirstTouch = false;
            if (mChangePosition) {
                // 进度调节：禁用 ViewPager2，停止进度条自动更新
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        }

        if (mChangePosition) {
            mControlWrapper.stopProgress();
            // e1.getX() - e2.getX() 产生正确的方向位移
            slideToChangePosition(e1.getX() - e2.getX());
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        mScaleGestureDetector.onTouchEvent(event);

        int pointerCount = event.getPointerCount();
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                if (pointerCount >= 2) {
                    mDownFocusX = (event.getX(0) + event.getX(1)) / 2f;
                    mDownFocusY = (event.getY(0) + event.getY(1)) / 2f;
                    View renderView = findRenderView();
                    if (renderView != null) {
                        mTranslateBaseX = renderView.getTranslationX();
                        mTranslateBaseY = renderView.getTranslationY();
                    }
                }
                MotionEvent cancelEvent = MotionEvent.obtain(event);
                cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                mGestureDetector.onTouchEvent(cancelEvent);
                cancelEvent.recycle();
                break;

            case MotionEvent.ACTION_MOVE:
                if (pointerCount >= 2 && !mIsScaling) {
                    float focusX = (event.getX(0) + event.getX(1)) / 2f;
                    float focusY = (event.getY(0) + event.getY(1)) / 2f;
                    float translateX = mTranslateBaseX + (focusX - mDownFocusX);
                    float translateY = mTranslateBaseY + (focusY - mDownFocusY);
                    View renderView = findRenderView();
                    if (renderView != null) {
                        applyTranslationBound(renderView, translateX, translateY);
                        notifyVideoTransformChanged();
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                long now = System.currentTimeMillis();
                if (now - mLastTwoFingerUpTime < DOUBLE_TAP_TIMEOUT) {
                    resetScale(mCurrentScale == 1.0f ? 3.0f : 1.0f);
                }
                mLastTwoFingerUpTime = now;
                break;
        }

        if (mIsScaling || pointerCount > 1) {
            if (action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL) {
                mIsScaling = false;
                mHasScaled = false;
                if (mCurrentScale <= 1.0f) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return true;
        }

        return mGestureDetector.onTouchEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);

        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mFirstTouch = true;
                mChangePosition = false;
                break;

            case MotionEvent.ACTION_MOVE:
                if (mChangePosition || mIsLongPressing) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mIsLongPressing) {
                    if (mIsBottomLongPress) {
                        // 下半部分长按：左右滑动调节 speedNode
                        float deltaX = event.getX() - mLongPressStartX;
                        slideToChangeSpeedNode(deltaX);
                    } else {
                        // 上半部分长按：下滑锁定逻辑
                        float deltaY = event.getY() - mLongPressY;
                        if (deltaY > mScaledTouchSlop) {
                            mChangeLock = true;
                        }
                    }
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mChangePosition) {
                    if (mSeekPosition >= 0) {
                        mControlWrapper.seekTo(mSeekPosition);
                    }
                    mBinding.tvProgress.setVisibility(GONE);
                    mControlWrapper.startProgress();
                    mControlWrapper.startFadeOut();
                }

                if (mIsLongPressing) {
                    if (mIsBottomLongPress) {
                        // 下半部分长按结束：隐藏 speedNode
                        mBinding.speedNode.setVisibility(GONE);
                    } else if (mChangeLock) {
                        if (mIsSpeedLocked) {
                            releaseSpeed();
                        } else {
                            lockSpeed();
                        }
                        mChangeLock = false;
                    } else {
                        if (!mIsSpeedLocked) {
                            resetSpeed();
                        }
                    }
                }

                mIsLongPressing = false;
                mIsBottomLongPress = false;
                mChangePosition = false;
                mSeekPosition = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    @SuppressLint("SetTextI18n")
    private void slideToChangePosition(float deltaX) {
        if (mControlWrapper == null) return;

        long duration = mControlWrapper.getDuration();
        if (duration <= 0) return;

        float width = getMeasuredWidth();
        if (width <= 0) return;

        long position = getPosition(deltaX, width, duration);

        mBinding.tvProgress.setVisibility(VISIBLE);
        mBinding.tvProgress.setText(PlayerUtils.stringForTime((int) position) + " / " + PlayerUtils.stringForTime((int) duration));
        mSeekPosition = (int) position;
    }

    private void slideToChangeSpeedNode(float deltaX) {
        if (mControlWrapper == null) return;

        float width = getMeasuredWidth();
        if (width <= 0) return;

        float minSpeed = 0.5f;
        float maxSpeed = 5.0f;
        float speedRange = maxSpeed - minSpeed;

        // deltaX > 0 向左滑（加速），deltaX < 0 向右滑（减速）
        // 滑动半个屏幕宽度覆盖整个速度范围
        float targetSpeed = mLongPressStartSpeed + deltaX / (width / 2) * speedRange;

        targetSpeed = Math.max(minSpeed, Math.min(maxSpeed, targetSpeed));
        float snappedSpeed = snapSpeed(targetSpeed);

        mBinding.speedNode.setValue(snappedSpeed);

        if (Float.compare(mCurrentSpeed, snappedSpeed) != 0) {
            mCurrentSpeed = snappedSpeed;
            mControlWrapper.setSpeed(mCurrentSpeed);
        }
    }

    private float snapSpeed(float speed) {
        float closestSpeed = SPEED_NODES.get(0);
        float minDiff = Math.abs(speed - closestSpeed);
        for (float nodeSpeed : SPEED_NODES) {
            float diff = Math.abs(speed - nodeSpeed);
            if (diff < minDiff) {
                minDiff = diff;
                closestSpeed = nodeSpeed;
            }
        }
        return closestSpeed;
    }

    private long getPosition(float deltaX, float width, long duration) {
        long currentPosition = mControlWrapper.getCurrentPosition();
        long maxSeekMs = 30000;
        long position = currentPosition - (long) (deltaX / width * maxSeekMs);
        position = Math.max(0, Math.min(position, duration));
        return position;
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }

    @Override
    public View getView() {
        return this;
    }

    public void setTitle(String title) {
        mBinding.tvTitle.setText(title);
    }

    public void setLike(boolean like) {
        mBinding.playLike.setSelected(like);
    }

    // === 自动沉浸体验(由 Activity 控制定时器,ControlView 只负责 enter/exit) ===

    public void setAutoImmersiveEnabled(boolean enabled) {
        if (!enabled) {
            exitImmersive();
        }
    }

    /** 进入沉浸:隐藏 UI 覆盖层 + 系统栏。 */
    public void enterImmersive() {
        immersiveMode = true;
        globalImmersiveMode = true;
        mBinding.tvTitle.setVisibility(GONE);
        mBinding.playLike.setVisibility(GONE);
        mBinding.layoutComment.setVisibility(GONE);
        mBinding.tvPage.setVisibility(GONE);
        mBinding.playSeekbar.setVisibility(GONE);
        mBinding.ivMore.setVisibility(GONE);
        mBinding.playBtn.setVisibility(GONE);
        mBinding.ivFull.setVisibility(GONE);
        if (mOnShortVideoListener != null) {
            mOnShortVideoListener.onChangeSysBar(false);
        }
    }

    /** 退出沉浸:恢复 UI 覆盖层 + 系统栏。 */
    public void exitImmersive() {
        immersiveMode = false;
        globalImmersiveMode = false;
        changeVisibility();
        if (mOnShortVideoListener != null) {
            mOnShortVideoListener.onChangeSysBar(true);
        }
    }

    public void changeVisibility() {
        if (globalImmersiveMode) return;
        mBinding.tvTitle.setVisibility(PlayerInitializer.Player.INSTANCE.getShortShowTitle() ? VISIBLE : GONE);
        mBinding.playLike.setVisibility(PlayerInitializer.Player.INSTANCE.getShortShowLike() ? VISIBLE : GONE);
        mBinding.layoutComment.setVisibility(PlayerInitializer.Player.INSTANCE.getShortShowComment() ? VISIBLE : GONE);
        mBinding.tvPage.setVisibility(PlayerInitializer.Player.INSTANCE.getShortShowPager() ? VISIBLE : GONE);
        mBinding.playSeekbar.setVisibility(PlayerInitializer.Player.INSTANCE.getShortShowSeekbar() ? VISIBLE : GONE);
        mBinding.ivMore.setVisibility(PlayerInitializer.Player.INSTANCE.getShortShowMore() ? VISIBLE : GONE);
    }

    @SuppressLint("SetTextI18n")
    public void setPage(int current, int total) {
        mBinding.tvPage.setText((current + 1) + " / " + total);
    }

    @SuppressLint("SetTextI18n")
    public void setCommentCount(int count) {
        if (count > 0) {
            if (count > 999) {
                mBinding.tvCommentCount.setText("999+");
            } else {
                mBinding.tvCommentCount.setText(String.valueOf(count));
            }
            mBinding.tvCommentCount.setVisibility(VISIBLE);
        } else {
            mBinding.tvCommentCount.setVisibility(GONE);
        }
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {

    }

    @Override
    public void onPlayStateChanged(int playState) {
        switch (playState) {
            case VideoView.STATE_IDLE:
                L.e("STATE_IDLE " + hashCode());
                resetSpeed();
                break;
            case VideoView.STATE_PLAYING:
                L.e("STATE_PLAYING " + hashCode());
                mBinding.playBtn.setVisibility(GONE);
                if (!immersiveMode) {
                    changeVisibility();
                    int[] videoSize = mControlWrapper.getVideoSize();
                    if (videoSize[1] / 9 * 16 <= videoSize[0]) {
                        mBinding.ivFull.setVisibility(VISIBLE);
                    } else {
                        mBinding.ivFull.setVisibility(GONE);
                    }
                }
                mControlWrapper.startProgress();
                break;
            case VideoView.STATE_PAUSED:
                L.e("STATE_PAUSED " + hashCode());
                mBinding.playBtn.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PREPARED:
                L.e("STATE_PREPARED " + hashCode());
                break;
            case VideoView.STATE_ERROR:
                L.e("STATE_ERROR " + hashCode());
                Toast.makeText(getContext(), me.lingci.lib.player.ui.R.string.dkplayer_error_message, Toast.LENGTH_SHORT).show();
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                if (PlayerInitializer.Player.INSTANCE.getShortAutoNext()) {
                    if (mOnShortVideoListener != null) {
                        mOnShortVideoListener.playNext();
                    }
                }
                break;
            default:

        }
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        switch (playerState) {
            case VideoView.PLAYER_NORMAL:
                setVisibility(VISIBLE);
                mBinding.playSeekbar.setEnabled(true);
                break;
            case VideoView.PLAYER_FULL_SCREEN:
                setVisibility(GONE);
                resetSpeed();
                break;
        }
    }

    @Override
    public void setProgress(int duration, int position) {
        if (mIsDragging) {
            return;
        }
        if (getVisibility() == GONE) {
            return;
        }
        if (duration > 0) {
            mBinding.playSeekbar.setEnabled(true);
            int pos = (int) (((double) position / duration) * mBinding.playSeekbar.getMax());
            mBinding.playSeekbar.setProgress(pos, true);
        } else {
            mBinding.playSeekbar.setEnabled(false);
        }
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {

    }

    @SuppressLint("DefaultLocale")
    private void adjustSpeed(boolean right) {
        if (right) {
            mCurrentSpeed = PlayerInitializer.Player.INSTANCE.getShortRightSpeed();
        } else {
            mCurrentSpeed = PlayerInitializer.Player.INSTANCE.getShortLeftSpeed();
        }
        mControlWrapper.setSpeed(mCurrentSpeed);
        mBinding.tvMessage.setText(String.format("%.1fx", mCurrentSpeed));
    }

    @SuppressLint("DefaultLocale")
    private void lockSpeed() {
        mIsSpeedLocked = true;
        mBinding.tvMessage.setVisibility(VISIBLE);
        mBinding.tvMessage.setText(String.format("已锁定 %.1fx", mCurrentSpeed));
    }

    private void releaseSpeed() {
        mIsSpeedLocked = false;
        resetSpeed();
        mBinding.tvMessage.setVisibility(GONE);
    }

    private void resetSpeed() {
        mCurrentSpeed = 1;
        mControlWrapper.setSpeed(mCurrentSpeed);
        hideSpeedIndicator();
    }

    private void showSpeedIndicator() {
        mBinding.tvMessage.setVisibility(VISIBLE);
        mBinding.tvMessage.setText(String.format(mIsSpeedLocked ? "已锁定 %.1fx 下滑解锁" : "%.1fx 下滑锁定", mCurrentSpeed));
    }

    private void hideSpeedIndicator() {
        mBinding.tvMessage.setVisibility(GONE);
    }

    @SuppressLint("SetTextI18n")
    public void updateTimerCloseDisplay(long millisUntilFinished) {
        if (millisUntilFinished > 0) {
            long minutes = millisUntilFinished / 60_000;
            long seconds = (millisUntilFinished / 1000) % 60;
            mBinding.tvMessage.setVisibility(VISIBLE);
            mBinding.tvMessage.setText(String.format("定时关闭 %d:%02d", minutes, seconds));
        } else {
            mBinding.tvMessage.setVisibility(GONE);
        }
    }

    // 4. 双击爱心动画实现
    private void showLikeAnimation(float x, float y) {
        // 这里的 iv_like_heart 是你需要在 layout 中准备的一个 ImageView 或动态创建
        // 建议使用类似抖音的随机角度和缩放动画
        ImageView imageView = new ImageView(getContext());
        imageView.setImageResource(R.drawable.ic_like); // 替换为你的红色爱心资源
        LayoutParams params = new LayoutParams(PlayerUtils.dp2px(getContext(), 60), PlayerUtils.dp2px(getContext(), 60));
        imageView.setLayoutParams(params);
        imageView.setX(x - params.width / 2f);
        imageView.setY(y - params.height / 2f);
        imageView.setRotation((float) (Math.random() * 60 - 30)); // 随机旋转 -30 ~ 30 度
        imageView.setClickable(false);
        addView(imageView);

        // 组合动画：弹出 + 缩小 + 透明度增加 + 向上漂移
        imageView.animate()
                .scaleX(1.2f).scaleY(1.2f).alpha(0f)
                .translationYBy(-300)
                .setDuration(800)
                .withEndAction(() -> removeView(imageView))
                .start();
    }

    @Override
    public boolean onDoubleTapEvent(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(@NonNull MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onScale(@NonNull ScaleGestureDetector detector) {
        View renderView = findRenderView();
        if (renderView == null) return true;

        // 只缩放真实渲染层，字幕和控制器仍保持在播放器容器坐标系里。
        float scaleFactor = detector.getScaleFactor();
        float newScale = mCurrentScale * scaleFactor;
        newScale = Math.max(1.0f, Math.min(5.0f, newScale));

        if (Math.abs(newScale - mCurrentScale) > 0.01f) {
            mHasScaled = true;
            mCurrentScale = newScale;

            renderView.setScaleX(mCurrentScale);
            renderView.setScaleY(mCurrentScale);
            Log.d(
                    SUBTITLE_GESTURE_TRACE_TAG,
                    "event=subtitle_zoom_state",
                    "phase=scale",
                    "scale=" + String.format(java.util.Locale.US, "%.3f", mCurrentScale),
                    "translationX=" + String.format(java.util.Locale.US, "%.1f", renderView.getTranslationX()),
                    "translationY=" + String.format(java.util.Locale.US, "%.1f", renderView.getTranslationY()),
                    "gestureActive=" + mIsScaling
            );
            notifyVideoTransformChanged();
        }

        return true;
    }

    @Override
    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
        mIsScaling = true;
        mHasScaled = false;
        mChangePosition = false;
        mFirstTouch = false;

        mDownFocusX = detector.getFocusX();
        mDownFocusY = detector.getFocusY();

        View renderView = findRenderView();
        if (renderView != null) {
            mTranslateBaseX = renderView.getTranslationX();
            mTranslateBaseY = renderView.getTranslationY();

            float currentScale = renderView.getScaleX();

            int[] videoScreenLoc = new int[2];
            renderView.getLocationOnScreen(videoScreenLoc);
            int viewLeft = videoScreenLoc[0];
            int viewTop = videoScreenLoc[1];
            int viewRight = viewLeft + renderView.getWidth();
            int viewBottom = viewTop + renderView.getHeight();

            int[] thisScreenLoc = new int[2];
            getLocationOnScreen(thisScreenLoc);
            float focusXScreen = thisScreenLoc[0] + mDownFocusX;
            float focusYScreen = thisScreenLoc[1] + mDownFocusY;

            float pivotX, pivotY;
            if (focusXScreen < viewLeft || focusXScreen > viewRight || focusYScreen < viewTop || focusYScreen > viewBottom) {
                pivotX = renderView.getWidth() / 2f;
                pivotY = renderView.getHeight() / 2f;
            } else {
                pivotX = (focusXScreen - viewLeft) / currentScale;
                pivotY = (focusYScreen - viewTop) / currentScale;
            }

            renderView.setPivotX(pivotX);
            renderView.setPivotY(pivotY);
            Log.d(
                    SUBTITLE_GESTURE_TRACE_TAG,
                    "event=subtitle_zoom_state",
                    "phase=scale_begin",
                    "scale=" + String.format(java.util.Locale.US, "%.3f", currentScale),
                    "translationX=" + String.format(java.util.Locale.US, "%.1f", renderView.getTranslationX()),
                    "translationY=" + String.format(java.util.Locale.US, "%.1f", renderView.getTranslationY()),
                    "gestureActive=true"
            );
        }

        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    @Override
    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
        mIsScaling = false;
        mHasScaled = false;
        View renderView = findRenderView();
        Log.d(
                SUBTITLE_GESTURE_TRACE_TAG,
                "event=subtitle_zoom_state",
                "phase=scale_end",
                "scale=" + String.format(java.util.Locale.US, "%.3f", mCurrentScale),
                "translationX=" + String.format(java.util.Locale.US, "%.1f", renderView != null ? renderView.getTranslationX() : 0f),
                "translationY=" + String.format(java.util.Locale.US, "%.1f", renderView != null ? renderView.getTranslationY() : 0f),
                "gestureActive=false"
        );
        if (mCurrentScale <= 1.0f) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    private void applyTranslationBound(View renderView, float translateX, float translateY) {
        int viewWidth = renderView.getWidth();
        int viewHeight = renderView.getHeight();

        if (viewWidth <= 0 || viewHeight <= 0) return;

        // 缩放为1时，不允许任何移动
        if (mCurrentScale <= 1.0f) {
            renderView.setTranslationX(0f);
            renderView.setTranslationY(0f);
            return;
        }

        // 平移边界按 renderView 缩放后的外扩尺寸计算，避免拖出播放器可见区域。
        float maxTranslateX = viewWidth * (mCurrentScale - 1f) / 2f;
        float maxTranslateY = viewHeight * (mCurrentScale - 1f) / 2f;

        translateX = Math.max(-maxTranslateX, Math.min(maxTranslateX, translateX));
        translateY = Math.max(-maxTranslateY, Math.min(maxTranslateY, translateY));

        renderView.setTranslationX(translateX);
        renderView.setTranslationY(translateY);
        Log.d(
                SUBTITLE_GESTURE_TRACE_TAG,
                "event=subtitle_zoom_state",
                "phase=translate",
                "scale=" + String.format(java.util.Locale.US, "%.3f", mCurrentScale),
                "translationX=" + String.format(java.util.Locale.US, "%.1f", translateX),
                "translationY=" + String.format(java.util.Locale.US, "%.1f", translateY),
                "gestureActive=" + mIsScaling
        );
    }

    public void resetScale(float scale) {
        mCurrentScale = Math.min(Math.max(scale, 1.0f), 5.0f);
        mIsScaling = false;
        mHasScaled = false;
        mTranslateBaseX = 0f;
        mTranslateBaseY = 0f;

        View renderView = findRenderView();
        if (renderView != null) {
            renderView.setPivotX(renderView.getWidth() / 2f);
            renderView.setPivotY(renderView.getHeight() / 2f);
            renderView.animate()
                    .scaleX(mCurrentScale)
                    .scaleY(mCurrentScale)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(200)
                    .withEndAction(this::notifyVideoTransformChanged)
                    .start();
        }
        Log.d(
                SUBTITLE_GESTURE_TRACE_TAG,
                "event=subtitle_zoom_state",
                "phase=reset",
                "scale=" + String.format(java.util.Locale.US, "%.3f", mCurrentScale),
                "translationX=0.0",
                "translationY=0.0",
                "gestureActive=false"
        );
        notifyVideoTransformChanged();
        getParent().requestDisallowInterceptTouchEvent(false);
    }

    public float getCurrentScale() {
        return mCurrentScale;
    }

    public boolean isScaleGestureActive() {
        return mIsScaling;
    }

    public boolean hasScaledVideo() {
        return mCurrentScale > 1.001f;
    }

    private View findVideoView() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return null;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof BaseVideoView) {
                return child;
            }
        }
        return null;
    }

    @Nullable
    private View findRenderView() {
        View videoView = findVideoView();
        if (videoView instanceof CustomVideoView) {
            // 短视频的几何变换和字幕停靠都以 renderView 为准，而不是外层 BaseVideoView。
            return ((CustomVideoView) videoView).getRenderTransformView();
        }
        return null;
    }

    private void notifyVideoTransformChanged() {
        if (mOnVideoTransformChangedListener != null) {
            mOnVideoTransformChangedListener.onVideoTransformChanged();
        }
    }

}
