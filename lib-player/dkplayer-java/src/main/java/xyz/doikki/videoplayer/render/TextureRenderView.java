package xyz.doikki.videoplayer.render;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import xyz.doikki.videoplayer.player.AbstractPlayer;

@SuppressLint("ViewConstructor")
public class TextureRenderView extends TextureView implements IRenderView, TextureView.SurfaceTextureListener {
    private final MeasureHelper mMeasureHelper;
    private SurfaceTexture mSurfaceTexture;

    @Nullable
    private AbstractPlayer mMediaPlayer;
    private Surface mSurface;

    public TextureRenderView(Context context) {
        super(context);
    }

    {
        mMeasureHelper = new MeasureHelper();
        setSurfaceTextureListener(this);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
        // 修复：从后台 Service 取回 player 后，render view 的 surface 已存在，
        // 但 onSurfaceTextureAvailable 不会重新触发（surface 一直 available），
        // 导致 player 拿不到 surface → 视频不渲染。
        // 这里主动把已存在的 surface 绑定到新 player。
        if (mSurface != null) {
            player.setSurface(mSurface);
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
        // getBitmap() 依赖 SurfaceTexture 的当前帧，必须在主线程取，否则可能拿到 null
        // 或与合成线程竞态。调用方（缩略图/用户截图）为了不阻塞 UI 会从后台线程调用，
        // 这里统一切回主线程执行，等待上限 2s；正常情况是几十毫秒。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return getBitmap();
        }
        final Bitmap[] result = new Bitmap[1];
        final CountDownLatch latch = new CountDownLatch(1);
        boolean posted = new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = getBitmap();
                } catch (Throwable ignored) {
                    // 截图失败不应影响播放，交由调用方按 null 处理
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!posted) {
            return null;
        }
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }

    @Override
    public void release() {
        if (mSurface != null)
            mSurface.release();

        if (mSurfaceTexture != null)
            mSurfaceTexture.release();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        if (mSurfaceTexture != null) {
            setSurfaceTexture(mSurfaceTexture);
        } else {
            mSurfaceTexture = surfaceTexture;
            mSurface = new Surface(surfaceTexture);
            if (mMediaPlayer != null) {
                mMediaPlayer.setSurface(mSurface);
            }
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }
}