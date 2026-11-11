package me.lingci.lib.player.widget.component;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.lingci.lib.player.listener.OnLongVideoListener;
import me.lingci.lib.player.listener.OnPlayNextListener;
import me.lingci.lib.player.listener.OnVisibilityChangedListener;
import me.lingci.lib.base.util.Log;
import me.lingci.lib.player.ui.R;
import me.lingci.lib.player.ui.databinding.LayoutCustomControlViewBinding;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

import java.util.Locale;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTime;

/**
 * 点播底部控制栏
 */
public class CustomControlView extends FrameLayout implements IControlComponent, View.OnClickListener, SeekBar.OnSeekBarChangeListener {

    private static final String TAG = "CustomControlView";

    protected ControlWrapper mControlWrapper;

    private final LayoutCustomControlViewBinding mBinding;

    private boolean needOrientation = false;

    private boolean mIsDragging;

    private boolean mIsShowBottomProgress = true;

    private OnVisibilityChangedListener onVisibilityChangedListener;
    private OnLongVideoListener mOnLongVideoListener;
    private OnPlayNextListener mOnPlayNextListener;

    public void setOnVisibilityChangedListener(OnVisibilityChangedListener onVisibilityChangedListener) {
        this.onVisibilityChangedListener = onVisibilityChangedListener;
    }

    public void setOnLongVideoListener(OnLongVideoListener onLongVideoListener) {
        this.mOnLongVideoListener = onLongVideoListener;
    }

    public void setOnPlayNextListener(OnPlayNextListener onPlayNextListener) {
        this.mOnPlayNextListener = onPlayNextListener;
    }

    public CustomControlView(@NonNull Context context) {
        super(context);
    }

    public CustomControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomControlView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        setVisibility(GONE);
        //LayoutInflater.from(getContext()).inflate(getLayoutId(), this, true);
        mBinding = LayoutCustomControlViewBinding.inflate(LayoutInflater.from(getContext()), this, true);
        mBinding.ivPlay.setOnClickListener(this);
        mBinding.fullscreen.setOnClickListener(this);
        mBinding.ivDmShow.setOnClickListener(this);
        mBinding.tvSelectDm.setOnClickListener(this);
        mBinding.ivDmConf.setOnClickListener(this);
        mBinding.seekBar.setOnSeekBarChangeListener(this);
        mBinding.ivPlayNext.setOnClickListener(this);
        mBinding.tvQuality.setOnClickListener(this);

        if (getContext() instanceof Activity) {
            needOrientation = ((Activity) getContext()).getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
    }

    protected int getLayoutId() {
        return R.layout.layout_custom_control_view;
    }

    /**
     * 是否显示底部进度条，默认显示
     */
    public void showBottomProgress(boolean isShow) {
        mIsShowBottomProgress = isShow;
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        if (isVisible) {
            mBinding.bottomContainer.setVisibility(VISIBLE);
            if (anim != null) {
                mBinding.bottomContainer.startAnimation(anim);
            }
            if (mIsShowBottomProgress) {
                mBinding.bottomProgress.setVisibility(GONE);
            }
        } else {
            mBinding.bottomContainer.setVisibility(GONE);
            if (anim != null) {
                mBinding.bottomContainer.startAnimation(anim);
            }
            if (mIsShowBottomProgress) {
                mBinding.bottomProgress.setVisibility(VISIBLE);
                AlphaAnimation animation = new AlphaAnimation(0f, 1f);
                animation.setDuration(300);
                mBinding.bottomProgress.startAnimation(animation);
            }
        }
        if (null != onVisibilityChangedListener) {
            onVisibilityChangedListener.onVisibilityChanged(isVisible);
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        // 缓冲信息只在缓冲期间展示
        if (playState != VideoView.STATE_BUFFERING) {
            mBinding.tvBufferInfo.setVisibility(GONE);
        }
        switch (playState) {
            case VideoView.STATE_IDLE:
            case VideoView.STATE_PLAYBACK_COMPLETED:
                setVisibility(GONE);
                mBinding.bottomProgress.setProgress(0);
                mBinding.bottomProgress.setSecondaryProgress(0);
                mBinding.seekBar.setProgress(0);
                mBinding.seekBar.setSecondaryProgress(0);
                break;
            case VideoView.STATE_START_ABORT:
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_ERROR:
                setVisibility(GONE);
                break;
            case VideoView.STATE_PLAYING:
                mBinding.ivPlay.setSelected(true);
                if (mIsShowBottomProgress) {
                    if (mControlWrapper.isShowing()) {
                        mBinding.bottomProgress.setVisibility(GONE);
                        mBinding.bottomContainer.setVisibility(VISIBLE);
                    } else {
                        mBinding.bottomContainer.setVisibility(GONE);
                        mBinding.bottomProgress.setVisibility(VISIBLE);
                    }
                } else {
                    // mBottomContainer
                    mBinding.bottomContainer.setVisibility(GONE);
                    mBinding.bottomProgress.setVisibility(GONE);
                }
                setVisibility(VISIBLE);
                // 开始刷新进度
                mControlWrapper.startProgress();
                break;
            case VideoView.STATE_PAUSED:
                mBinding.ivPlay.setSelected(false);
                break;
            case VideoView.STATE_BUFFERING:
                mBinding.ivPlay.setSelected(mControlWrapper.isPlaying());
                mBinding.tvBufferInfo.setVisibility(VISIBLE);
                updateBufferInfo();
                break;
            case VideoView.STATE_BUFFERED:
                mBinding.ivPlay.setSelected(mControlWrapper.isPlaying());
                break;
        }
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        switch (playerState) {
            case VideoView.PLAYER_NORMAL:
                mBinding.fullContainer.setVisibility(GONE);
                mBinding.fullscreen.setSelected(false);
                break;
            case VideoView.PLAYER_FULL_SCREEN:
                mBinding.fullContainer.setVisibility(VISIBLE);
                mBinding.fullscreen.setSelected(true);
                break;
            default:
                mBinding.fullContainer.setVisibility(GONE);
                break;
        }

        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity != null && mControlWrapper.hasCutout()) {
            int orientation = activity.getRequestedOrientation();
            int cutoutHeight = mControlWrapper.getCutoutHeight();
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                mBinding.bottomContainer.setPadding(0, 0, 0, 0);
                mBinding.bottomProgress.setPadding(0, 0, 0, 0);
            } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                mBinding.bottomContainer.setPadding(cutoutHeight, 0, 0, 0);
                mBinding.bottomProgress.setPadding(cutoutHeight, 0, 0, 0);
            } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                mBinding.bottomContainer.setPadding(0, 0, cutoutHeight, 0);
                mBinding.bottomProgress.setPadding(0, 0, cutoutHeight, 0);
            }
        }
    }

    @Override
    public void setProgress(int duration, int position) {
        if (mIsDragging) {
            return;
        }

        if (duration > 0) {
            mBinding.seekBar.setEnabled(true);
            int pos = (int) (position * 1.0 / duration * mBinding.seekBar.getMax());
            mBinding.seekBar.setProgress(pos);
            mBinding.bottomProgress.setProgress(pos);
        } else {
            mBinding.seekBar.setEnabled(false);
        }
        int percent = mControlWrapper.getBufferedPercentage();
        if (percent >= 95) { //解决缓冲进度不能100%问题
            mBinding.seekBar.setSecondaryProgress(mBinding.seekBar.getMax());
            mBinding.bottomProgress.setSecondaryProgress(mBinding.bottomProgress.getMax());
        } else {
            mBinding.seekBar.setSecondaryProgress(percent * 10);
            mBinding.bottomProgress.setSecondaryProgress(percent * 10);
        }

        mBinding.totalTime.setText(stringForTime(duration));
        mBinding.currTime.setText(stringForTime(position));

        // 缓冲期间进度回调仍在刷新，顺带更新缓存时长与网速
        if (mBinding.tvBufferInfo.getVisibility() == VISIBLE) {
            updateBufferInfo();
        }
    }

    /**
     * 刷新缓冲可视化信息（缓存时长 + 实时网速），并输出弱网诊断日志
     */
    private void updateBufferInfo() {
        long cachedMs = Math.max(0L, mControlWrapper.getCachedDuration());
        long speed = Math.max(0L, mControlWrapper.getTcpSpeed());
        mBinding.tvBufferInfo.setText(
                getContext().getString(R.string.buffer_info, cachedMs / 1000, formatSpeed(speed)));
        Log.d(TAG, "buffering", "cached=" + cachedMs + "ms", "tcpSpeed=" + speed + "B/s");
    }

    /**
     * 网速格式化：B/s、KB/s、MB/s
     */
    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(Locale.US, "%.1fMB", bytesPerSecond / 1024f / 1024f);
        }
        if (bytesPerSecond >= 1024) {
            return String.format(Locale.US, "%dKB", bytesPerSecond / 1024);
        }
        return bytesPerSecond + "B";
    }

    /**
     * 是否存在多档清晰度；无多档时隐藏入口
     */
    public void setQualityAvailable(boolean available) {
        mBinding.tvQuality.setVisibility(available ? VISIBLE : GONE);
    }

    /**
     * 更新清晰度入口文案，传入当前档位名称；为空时显示默认文案
     */
    public void setQualityName(String name) {
        if (name == null || name.trim().isEmpty()) {
            mBinding.tvQuality.setText(R.string.action_quality);
        } else {
            mBinding.tvQuality.setText(name);
        }
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        onVisibilityChanged(!isLocked, null);
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.iv_dm_show) {
            showDm(!mBinding.ivDmShow.isSelected());
            if (null != mOnLongVideoListener) {
                mOnLongVideoListener.onDmShow(mBinding.ivDmShow.isSelected());
            }
        }
        if (v.getId() == R.id.tv_select_dm) {
            if (null != mOnLongVideoListener) {
                mOnLongVideoListener.onSelectDm();
            }
        }
        if (v.getId() == R.id.iv_dm_conf) {
            if (null != mOnLongVideoListener) {
                mOnLongVideoListener.onConfDm();
            }
        }
        if (v.getId() == R.id.fullscreen) {
            toggleFullScreen();
        }
        if (v.getId() == R.id.iv_play) {
            mControlWrapper.togglePlay();
        }
        if (v.getId() == R.id.iv_play_next) {
            if (null != mOnPlayNextListener) {
                mOnPlayNextListener.onNextPlay();
            }
        }
        if (v.getId() == R.id.tv_quality) {
            if (null != mOnLongVideoListener) {
                mOnLongVideoListener.onShowQualitySelect();
            }
        }
    }

    public void showDm(boolean showDm) {
        mBinding.ivDmShow.setSelected(showDm);
    }

    /**
     * 横竖屏切换
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private void toggleFullScreen() {
        Activity activity = PlayerUtils.scanForActivity(getContext());

        if (needOrientation) {
            if (mControlWrapper.isFullScreen()) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } else {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        }

        //mControlWrapper.toggleFullScreen(activity);
        mControlWrapper.toggleFullScreen();
        // 下面方法会根据适配宽高决定是否旋转屏幕
        // mControlWrapper.toggleFullScreenByVideoSize(activity);
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        mIsDragging = true;
        mControlWrapper.stopProgress();
        mControlWrapper.stopFadeOut();
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        long duration = mControlWrapper.getDuration();
        long newPosition = (duration * seekBar.getProgress()) / mBinding.seekBar.getMax();
        mControlWrapper.seekTo((int) newPosition);
        mIsDragging = false;
        mControlWrapper.startProgress();
        mControlWrapper.startFadeOut();
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (!fromUser) {
            return;
        }

        long duration = mControlWrapper.getDuration();
        long newPosition = (duration * progress) / mBinding.seekBar.getMax();
        mBinding.currTime.setText(stringForTime((int) newPosition));
    }

}