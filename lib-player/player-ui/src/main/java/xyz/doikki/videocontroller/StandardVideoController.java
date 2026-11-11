package xyz.doikki.videocontroller;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.lingci.lib.player.ui.R;
import me.lingci.lib.player.ui.databinding.DkplayerLayoutStandardControllerBinding;
import xyz.doikki.videocontroller.component.CompleteView;
import xyz.doikki.videocontroller.component.ErrorView;
import xyz.doikki.videocontroller.component.GestureView;
import xyz.doikki.videocontroller.component.LiveControlView;
import xyz.doikki.videocontroller.component.PrepareView;
import xyz.doikki.videocontroller.component.TitleView;
import xyz.doikki.videocontroller.component.VodControlView;
import xyz.doikki.videoplayer.controller.GestureVideoController;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 直播/点播控制器
 * 注意：此控制器仅做一个参考，如果想定制ui，你可以直接继承GestureVideoController或者BaseVideoController实现
 * 你自己的控制器
 * Created by Doikki on 2017/4/7.
 */

public class StandardVideoController extends GestureVideoController implements View.OnClickListener {

    protected DkplayerLayoutStandardControllerBinding mBinding;

    private boolean isBuffering;

    protected boolean showTimeSync = false;

    public StandardVideoController(@NonNull Context context) {
        this(context, null);
    }

    public StandardVideoController(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StandardVideoController(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected int getLayoutId() {
        //return R.layout.dkplayer_layout_standard_controller;
        return 0;
    }

    @Override
    protected void initView() {
        super.initView();
        mBinding = DkplayerLayoutStandardControllerBinding.inflate(
                LayoutInflater.from(getContext()), this, true
        );
        mBinding.lock.setOnClickListener(this);
    }

    /**
     * 快速添加各个组件
     * @param title  标题
     * @param isLive 是否为直播
     */
    public void addDefaultControlComponent(String title, boolean isLive) {
        CompleteView completeView = new CompleteView(getContext());
        ErrorView errorView = new ErrorView(getContext());
        PrepareView prepareView = new PrepareView(getContext());
        prepareView.setClickStart();
        TitleView titleView = new TitleView(getContext());
        titleView.setTitle(title);
        addControlComponent(completeView, errorView, prepareView, titleView);
        if (isLive) {
            addControlComponent(new LiveControlView(getContext()));
        } else {
            addControlComponent(new VodControlView(getContext()));
        }
        addControlComponent(new GestureView(getContext()));
        setCanChangePosition(!isLive);
    }

    @Override
    public void onClick(View v) {
        int i = v.getId();
        if (i == R.id.lock) {
            mControlWrapper.toggleLockState();
        }
    }

    @Override
    protected void onLockStateChanged(boolean isLocked) {
        if (isLocked) {
            mBinding.lock.setSelected(true);
            if (showTimeSync) {
                mBinding.timeSync.setVisibility(VISIBLE);
            }
        } else {
            mBinding.lock.setSelected(false);
            if (showTimeSync) {
                mBinding.timeSync.setVisibility(GONE);
            }
        }
    }

    @Override
    protected void onVisibilityChanged(boolean isVisible, Animation anim) {
        if (mControlWrapper.isFullScreen()) {
            if (isVisible) {
                if (mBinding.layoutLeft.getVisibility() == GONE) {
                    mBinding.layoutLeft.setVisibility(VISIBLE);
                    if (anim != null) {
                        mBinding.layoutLeft.startAnimation(anim);
                    }
                }
            } else {
                mBinding.layoutLeft.setVisibility(GONE);
                if (anim != null) {
                    mBinding.layoutLeft.startAnimation(anim);
                }
            }
        }
    }

    @Override
    protected void onPlayerStateChanged(int playerState) {
        super.onPlayerStateChanged(playerState);
        switch (playerState) {
            case VideoView.PLAYER_NORMAL:
                setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                mBinding.layoutLeft.setVisibility(GONE);
                break;
            case VideoView.PLAYER_FULL_SCREEN:
                if (isShowing()) {
                    mBinding.layoutLeft.setVisibility(VISIBLE);
                } else {
                    mBinding.layoutLeft.setVisibility(GONE);
                }
                break;
        }
        // updateLockLayout();
    }

    private void updateLockLayout() {
        if (mActivity != null && hasCutout()) {
            int orientation = mActivity.getRequestedOrientation();
            int dp24 = PlayerUtils.dp2px(getContext(), 24);
            int cutoutHeight = getCutoutHeight();
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                FrameLayout.LayoutParams lblp = (LayoutParams) mBinding.layoutLeft.getLayoutParams();
                lblp.setMargins(dp24, 0, dp24, 0);
            } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                FrameLayout.LayoutParams layoutParams = (LayoutParams) mBinding.layoutLeft.getLayoutParams();
                layoutParams.setMargins(dp24 + cutoutHeight, 0, dp24 + cutoutHeight, 0);
            } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                FrameLayout.LayoutParams layoutParams = (LayoutParams) mBinding.layoutLeft.getLayoutParams();
                layoutParams.setMargins(dp24, 0, dp24, 0);
            }
        }
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        switch (playState) {
            //调用release方法会回到此状态
            case VideoView.STATE_IDLE:
                isBuffering = false;
                mBinding.lock.setSelected(false);
                mBinding.loading.setVisibility(GONE);
                break;
            case VideoView.STATE_PLAYING:
            case VideoView.STATE_PAUSED:
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_ERROR:
            case VideoView.STATE_BUFFERED:
                // 走到任何非缓冲状态都说明缓冲阶段已经结束。旧实现只在 STATE_BUFFERED
                // 清 isBuffering，而 BaseVideoView 在用户暂停时会把 BUFFERING_END 派成
                // STATE_PAUSED，导致该标志永久为 true、中央 loading 圈再也不消失。
                isBuffering = false;
                mBinding.loading.setVisibility(GONE);
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                mBinding.loading.setVisibility(VISIBLE);
                if (playState == VideoView.STATE_BUFFERING) {
                    isBuffering = true;
                }
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                isBuffering = false;
                mBinding.loading.setVisibility(GONE);
                mBinding.layoutLeft.setVisibility(GONE);
                mBinding.lock.setSelected(false);
                break;
        }
    }

    @Override
    public boolean onBackPressed() {
        if (isLocked()) {
            show();
            Toast.makeText(getContext(), R.string.dkplayer_lock_tip, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (mControlWrapper.isFullScreen()) {
            return stopFullScreen();
        }
        return super.onBackPressed();
    }

    public void setTimeSyncListener(OnClickListener listener) {
        showTimeSync = true;
        mBinding.timeSync.setOnClickListener(listener);
    }

}
