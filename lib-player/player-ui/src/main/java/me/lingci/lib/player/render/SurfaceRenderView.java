package me.lingci.lib.player.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.MeasureHelper;
import me.lingci.lib.player.util.SurfaceRenderTrace;

public class SurfaceRenderView extends SurfaceView implements IRenderView, SurfaceHolder.Callback {
    private final MeasureHelper mMeasureHelper;

    private AbstractPlayer mMediaPlayer;

    private boolean mHasBoundDisplay = false;

    public SurfaceRenderView(Context context) {
        super(context);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        mMeasureHelper = new MeasureHelper();
        SurfaceHolder surfaceHolder = getHolder();
        // 关键修复：设置像素格式为RGBA_8888，避免部分GPU在默认RGB_565格式下渲染黑屏
        surfaceHolder.setFormat(PixelFormat.RGBA_8888);
        surfaceHolder.addCallback(this);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
        mHasBoundDisplay = false;
        SurfaceRenderTrace.d("SurfaceRenderView", "attachToPlayer player=" + player.getClass().getSimpleName());
        // 修复：从后台 Service 取回 player 后，surface 已存在但回调不会重新触发，
        // 主动把已存在的 holder 绑定到新 player。
        SurfaceHolder holder = getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            player.setDisplay(holder);
            mHasBoundDisplay = true;
        }
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            requestLayout();
        }
    }

    @Override
    public void setVideoRotation(int degree) {
        mMeasureHelper.setVideoRotation(degree);
        setRotation(degree);
    }

    @Override
    public void setScaleType(int scaleType) {
        mMeasureHelper.setScreenScale(scaleType);
        requestLayout();
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public Bitmap doScreenShot() {
        return null;
    }

    @Override
    public void release() {
        SurfaceRenderTrace.d("SurfaceRenderView", "release bound=" + mHasBoundDisplay);
        mHasBoundDisplay = false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (mMediaPlayer != null) {
            SurfaceRenderTrace.d("SurfaceRenderView", "surfaceCreated surface=" + holder.getSurface()
                    + " valid=" + (holder.getSurface() != null && holder.getSurface().isValid())
                    + " frame=" + holder.getSurfaceFrame());
            // Do not block bind by isValid() here; some ROMs report false even after callback.
            mMediaPlayer.setDisplay(holder);
            mHasBoundDisplay = true;
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        SurfaceRenderTrace.d("SurfaceRenderView", "surfaceChanged width=" + width + " height=" + height
                + " format=" + format + " valid=" + (holder.getSurface() != null && holder.getSurface().isValid())
                + " bound=" + mHasBoundDisplay);
        if (mMediaPlayer != null && !mHasBoundDisplay) {
            mMediaPlayer.setDisplay(holder);
            mHasBoundDisplay = true;
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        SurfaceRenderTrace.d("SurfaceRenderView", "surfaceDestroyed surface=" + holder.getSurface()
                + " valid=" + (holder.getSurface() != null && holder.getSurface().isValid())
                + " bound=" + mHasBoundDisplay);
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(null);
        }
        mHasBoundDisplay = false;
    }
}
