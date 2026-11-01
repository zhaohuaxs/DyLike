package me.lingci.dy.player.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xyz.doikki.videocontroller.component.GestureView;
import xyz.doikki.videoplayer.controller.GestureVideoController;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 短视频
 * Created by Doikki on 2018/1/6.
 */
public class ShortVideoController extends GestureVideoController {

    private VideoFullControlView mVideoFullControlView;

    public ShortVideoController(@NonNull Context context) {
        super(context);
    }

    public ShortVideoController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ShortVideoController(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected int getLayoutId() {
        // R.layout.layout_tiktok_controller;
        return 0;
    }


    @Override
    protected void initView() {
        super.initView();
        // 滑动控制视图
        addControlComponent(new GestureView(getContext()));
        // 竖屏手势
        setEnableInNormal(false);
        mVideoFullControlView = new VideoFullControlView(getContext());
        addControlComponent(mVideoFullControlView);

    }

    public void addShortVideoControlView(IControlComponent component) {
        // 请点进去看isDissociate的解释
        addControlComponent(component, true);
    }


    public void setTitle(String name) {
        mVideoFullControlView.setTitle(name);
    }

    @Override
    public boolean showNetWarning() {
        // 不显示移动网络播放警告
        return false;
    }

    @Override
    public boolean onBackPressed() {
        if (mControlWrapper != null && mControlWrapper.isFullScreen()) {
            mControlWrapper.toggleFullScreen(PlayerUtils.scanForActivity(getContext()));
            return true;
        }
        return super.onBackPressed();
    }

}
