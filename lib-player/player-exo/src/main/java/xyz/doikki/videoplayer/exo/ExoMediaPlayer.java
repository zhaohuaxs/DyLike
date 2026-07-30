package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.util.EventLogger;

import java.util.Map;

import me.lingci.lib.base.util.Log;
import me.lingci.lib.player.exo.FfmpegRenderersFactory;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewManager;
import me.lingci.lib.player.util.SurfaceRenderTrace;

@UnstableApi
public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    protected Context mAppContext;
    protected ExoPlayer mInternalPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;

    private PlaybackParameters mSpeedPlaybackParameters;

    private boolean mIsPreparing;

    private LoadControl mLoadControl;
    private RenderersFactory mRenderersFactory;
    private TrackSelector mTrackSelector;

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
        // #7 FfmpegRenderersFactory 在构造函数创建，setExtensionRendererMode 移到 initPlayer 中统一处理
        mRenderersFactory = new FfmpegRenderersFactory(context);
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void initPlayer() {
        // #7 仅当 RenderersFactory 为 FfmpegRenderersFactory 时启用扩展渲染器模式
        if (mRenderersFactory instanceof FfmpegRenderersFactory) {
            ((DefaultRenderersFactory) mRenderersFactory).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        } else if (mRenderersFactory == null) {
            mRenderersFactory = new DefaultRenderersFactory(mAppContext);
        }
        if (mTrackSelector == null) {
            mTrackSelector = new DefaultTrackSelector(mAppContext);
        }
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl();
        }
        // #6 替代已废弃的 DefaultBandwidthMeter.getSingletonInstance()
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(mAppContext).build();
        mInternalPlayer = new ExoPlayer.Builder(
                mAppContext,
                mRenderersFactory,
                new DefaultMediaSourceFactory(mAppContext),
                mTrackSelector,
                mLoadControl,
                bandwidthMeter,
                new DefaultAnalyticsCollector(Clock.DEFAULT))
                .build();
        setOptions();
        Log.d(this, "initPlayer", mRenderersFactory.getClass().getName(), mTrackSelector.getClass().getName());

        // 播放器日志
        if (VideoViewManager.getConfig().mIsEnableLog && mTrackSelector instanceof MappingTrackSelector) {
            mInternalPlayer.addAnalyticsListener(new EventLogger("player-exo"));
        }

        mInternalPlayer.addListener(this);
    }

    public void setTrackSelector(TrackSelector trackSelector) {
        mTrackSelector = trackSelector;
    }

    public void setRenderersFactory(RenderersFactory renderersFactory) {
        mRenderersFactory = renderersFactory;
    }

    public void setLoadControl(LoadControl loadControl) {
        mLoadControl = loadControl;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        mMediaSource = mMediaSourceHelper.getMediaSource(path, headers);
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        //no support
    }

    @Override
    public void start() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.stop();
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void prepareAsync() {
        if (mInternalPlayer == null)
            return;
        if (mMediaSource == null) return;
        SurfaceRenderTrace.d("ExoMediaPlayer", "prepareAsync mediaSource=" + mMediaSource.getClass().getSimpleName());
        if (mSpeedPlaybackParameters != null) {
            mInternalPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource);
        mInternalPlayer.prepare();
    }

    @Override
    public void reset() {
        if (mInternalPlayer != null) {
            mInternalPlayer.stop();
            mInternalPlayer.clearMediaItems();
            mInternalPlayer.setVideoSurface(null);
            mIsPreparing = false;
        }
    }

    @Override
    public boolean isPlaying() {
        if (mInternalPlayer == null)
            return false;
        int state = mInternalPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mInternalPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mInternalPlayer != null) {
            mInternalPlayer.removeListener(this);
            mInternalPlayer.release();
            mInternalPlayer = null;
        }

        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getBufferedPercentage();
    }

    @Override
    public void setSurface(Surface surface) {
        if (mInternalPlayer != null) {
            SurfaceRenderTrace.d("ExoMediaPlayer", "setSurface surface=" + surface + " valid=" + (surface != null && surface.isValid()));
            mInternalPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null) {
            if (mInternalPlayer != null) {
                SurfaceRenderTrace.d("ExoMediaPlayer", "setDisplay null clearVideoSurface");
                mInternalPlayer.clearVideoSurface();
            }
        } else {
            Surface surface = holder.getSurface();
            SurfaceRenderTrace.d("ExoMediaPlayer", "setDisplay holder surface=" + surface + " valid=" + (surface != null && surface.isValid()));
            if (surface != null) {
                setSurface(surface);
            }
        }
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mInternalPlayer != null)
            mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mInternalPlayer != null)
            mInternalPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        //准备好就开始播放
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mInternalPlayer != null) {
            mInternalPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        // no support
        return 0;
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        SurfaceRenderTrace.d("ExoMediaPlayer", "onPlaybackStateChanged state=" + playbackState + " preparing=" + mIsPreparing);
        if (mPlayerEventListener == null) return;
        PlaybackStateResolver.Action action = PlaybackStateResolver.resolve(playbackState, mIsPreparing);
        switch (action) {
            case CALL_PREPARED:
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
                break;
            case CALL_COMPLETION:
                mIsPreparing = false;
                mPlayerEventListener.onCompletion();
                break;
            case CALL_BUFFERING_START:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case CALL_BUFFERING_END:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case NONE:
            default:
                break;
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        SurfaceRenderTrace.e("ExoMediaPlayer", "onPlayerError code=" + error.errorCode + " " + error.getMessage());
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        SurfaceRenderTrace.d("ExoMediaPlayer", "onVideoSizeChanged " + videoSize.width + "x" + videoSize.height + " rotation=" + videoSize.unappliedRotationDegrees);
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        SurfaceRenderTrace.d("ExoMediaPlayer", "onRenderedFirstFrame");
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
        }
    }
}
