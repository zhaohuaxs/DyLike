package xyz.doikki.videoplayer.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.lingci.lib.base.view.ParticleEffectLayout;
import xyz.doikki.android.dkplayer.R;
import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.controller.MediaPlayerControl;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.util.L;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 带泛型的播放器，可继承 AbstractPlayer 扩展自己的播放器
 * Created by Doikki on 2017/4/7.
 */
public class BaseVideoView<P extends AbstractPlayer> extends FrameLayout
        implements MediaPlayerControl, AbstractPlayer.PlayerEventListener {

    protected P mMediaPlayer;//播放器
    protected PlayerFactory<P> mPlayerFactory;//工厂类，用于实例化播放核心
    @Nullable
    protected BaseVideoController mVideoController;//控制器

    /**
     * 真正承载播放器视图的容器
     */
    protected ParticleEffectLayout mPlayerContainer;

    protected IRenderView mRenderView;
    protected RenderViewFactory mRenderViewFactory;

    public static final int SCREEN_SCALE_DEFAULT = 0;
    public static final int SCREEN_SCALE_16_9 = 1;
    public static final int SCREEN_SCALE_4_3 = 2;
    public static final int SCREEN_SCALE_MATCH_PARENT = 3;
    public static final int SCREEN_SCALE_ORIGINAL = 4;
    public static final int SCREEN_SCALE_CENTER_CROP = 5;
    protected int mCurrentScreenScaleType;

    protected int[] mVideoSize = {0, 0};

    protected boolean mIsMute;//是否静音

    //--------- data sources ---------//
    protected String mUrl;//当前播放视频的地址
    protected Map<String, String> mHeaders;//当前视频地址的请求头
    protected AssetFileDescriptor mAssetFileDescriptor;//assets文件

    protected long mCurrentPosition;//当前正在播放视频的位置

    /**
     * 用户播放意图，与当前瞬时状态分离。PREPARING/BUFFERING 时也必须被尊重，
     * 否则一次后台 pause 会被后续 ready/first-frame 回调覆盖。
     */
    protected boolean mPlayWhenReady = true;

    /** player 已交给后台 Service，但 render view 仍保留。 */
    protected boolean mPlayerDetached;

    //播放器的各种状态
    public static final int STATE_ERROR = -1;
    public static final int STATE_IDLE = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_PREPARED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_PAUSED = 4;
    public static final int STATE_PLAYBACK_COMPLETED = 5;
    public static final int STATE_BUFFERING = 6;
    public static final int STATE_BUFFERED = 7;
    public static final int STATE_START_ABORT = 8;//开始播放中止
    protected int mCurrentPlayState = STATE_IDLE;//当前播放器的状态

    public static final int PLAYER_NORMAL = 10;        // 普通播放器
    public static final int PLAYER_FULL_SCREEN = 11;   // 全屏播放器
    public static final int PLAYER_TINY_SCREEN = 12;   // 小屏播放器
    protected int mCurrentPlayerState = PLAYER_NORMAL;

    protected boolean mIsFullScreen;//是否处于全屏状态

    protected boolean mIsTinyScreen;//是否处于小屏状态
    protected int[] mTinyScreenSize = {0, 0};

    /**
     * 监听系统中音频焦点改变，见{@link #setEnableAudioFocus(boolean)}
     */
    protected boolean mEnableAudioFocus;
    @Nullable
    protected AudioFocusHelper mAudioFocusHelper;

    /**
     * 音频渐入渐出辅助类
     */
    @Nullable
    protected AudioFadeHelper mAudioFadeHelper;

    /**
     * OnStateChangeListener集合，保存了所有开发者设置的监听器
     */
    protected List<OnStateChangeListener> mOnStateChangeListeners;

    /**
     * 进度管理器，设置之后播放器会记录播放进度，以便下次播放恢复进度
     */
    @Nullable
    protected ProgressManager mProgressManager;

    /**
     * 循环播放
     */
    protected boolean mIsLooping;

    /**
     * {@link #mPlayerContainer}背景色，默认黑色
     */
    private final int mPlayerBackgroundColor;

    public BaseVideoView(@NonNull Context context) {
        this(context, null);
    }

    public BaseVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @SuppressWarnings({"unchecked", "resource"})
    public BaseVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        //读取全局配置
        VideoViewConfig config = VideoViewManager.getConfig();
        mEnableAudioFocus = config.mEnableAudioFocus;
        mProgressManager = config.mProgressManager;
        mPlayerFactory = config.mPlayerFactory;
        mCurrentScreenScaleType = config.mScreenScaleType;
        mRenderViewFactory = config.mRenderViewFactory;

        //初始化音频渐入渐出
        mAudioFadeHelper = new AudioFadeHelper(this);
        mAudioFadeHelper.setEnabled(config.mEnableAudioFade);
        mAudioFadeHelper.setFadeInDuration(config.mAudioFadeInDuration);
        mAudioFadeHelper.setFadeOutDuration(config.mAudioFadeOutDuration);

        //读取xml中的配置，并综合全局配置
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.BaseVideoView);
        mEnableAudioFocus = a.getBoolean(R.styleable.BaseVideoView_enableAudioFocus, mEnableAudioFocus);
        mIsLooping = a.getBoolean(R.styleable.BaseVideoView_looping, false);
        mCurrentScreenScaleType = a.getInt(R.styleable.BaseVideoView_screenScaleType, mCurrentScreenScaleType);
        mPlayerBackgroundColor = a.getColor(R.styleable.BaseVideoView_playerBackgroundColor, Color.BLACK);
        a.recycle();

        initView();
    }

    /**
     * 初始化播放器视图
     */
    protected void initView() {
        mPlayerContainer = new ParticleEffectLayout(getContext());
        mPlayerContainer.setBackgroundColor(mPlayerBackgroundColor);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        this.addView(mPlayerContainer, params);
    }

    /**
     * 设置{@link #mPlayerContainer}的背景色
     */
    public void setPlayerBackgroundColor(int color) {
        mPlayerContainer.setBackgroundColor(color);
    }

    /**
     * 开始播放，注意：调用此方法后必须调用{@link #release()}释放播放器，否则会导致内存泄漏
     */
    @Override
    public void start() {
        mPlayWhenReady = true;
        if (isInIdleState()
                || isInStartAbortState()
                || mMediaPlayer == null) {
            startPlay();
            return;
        }
        if (mCurrentPlayState == STATE_ERROR) {
            // 可恢复错误的重试必须重建/重置源，不能对错误态 player 直接 start()。
            release();
            startPlay();
            return;
        }
        if (mCurrentPlayState == STATE_PLAYBACK_COMPLETED) {
            replay(false);
            return;
        }
        startInPlaybackState();
    }

    /**
     * 切换播放源前禁用视频输出，清旧帧避免 surface 重建残影。
     * 可复用内核（mpv）用 vid=no 清 framebuffer；不可复用内核（exo）release 销毁 player，
     * reparent 时 surfaceChanged 检查 mMediaPlayer==null 不绑定旧帧，避免拉伸。
     */
    public void suppressVideoForSwitch() {
        if (mMediaPlayer != null) {
            if (mMediaPlayer.canReuseForNewMedia()) {
                mMediaPlayer.suppressVideoForSwitch();
            } else {
                release();
            }
        }
    }

    /**
     * 切换视频源：调用前先 {@link #setUrl(String, Map)}。
     * 后端声明可复用时保留 player/render view，只 reset+prepare 新源；
     * 否则退回完整的 release + start。该方法避免长/短视频每次都重建 MPV。
     */
    public void startOrReplace() {
        if (mMediaPlayer != null && mRenderView != null
                && mMediaPlayer.canReuseForNewMedia()) {

            try {
                // 进度快照已由 setUrl 在切换 mUrl 之前完成，这里不能再存一次，
                // 否则旧位置会被写到新媒体的进度上。
                mMediaPlayer.setPlayerEventListener(this);
                mMediaPlayer.reset();
                mMediaPlayer.setSpeed(1f);
                mPlayWhenReady = true;
                setOptions();
                startPrepareAfterReset();
            } catch (Exception e) {
                L.e("startOrReplace failed, rebuilding player: " + e.getMessage());
                e.printStackTrace();
                release();
                start();
            }
            return;
        }
        release();
        start();
    }

    /**
     * 第一次播放
     * @return 是否成功开始播放
     */
    @SuppressWarnings("UnusedReturnValue")
    protected boolean startPlay() {
        //如果要显示移动网络提示则不继续播放
        if (showNetWarning()) {
            //中止播放
            setPlayState(STATE_START_ABORT);
            return false;
        }
        //监听音频焦点改变
        if (mEnableAudioFocus) {
            mAudioFocusHelper = new AudioFocusHelper(this);
            if (!mIsMute) {
                // 未获批时不直接依赖底层 prepare 后自动出声，onPrepared 会保持暂停。
                mAudioFocusHelper.requestFocus();
            }
        }
        mPlayWhenReady = true;
        mPlayerDetached = false;
        //读取播放进度
        if (mProgressManager != null) {
            mCurrentPosition = mProgressManager.getSavedProgress(mUrl);
        }
        initPlayer();
        addDisplay();
        startPrepare(false);
        return true;
    }

    /**
     * 是否显示移动网络提示，可在Controller中配置
     */
    protected boolean showNetWarning() {
        //播放本地数据源时不检测网络
        if (isLocalDataSource()) return false;
        return mVideoController != null && mVideoController.showNetWarning();
    }

    /**
     * 判断是否为本地数据源，包括 本地文件、Asset、raw
     */
    protected boolean isLocalDataSource() {
        if (mAssetFileDescriptor != null) {
            return true;
        } else if (!TextUtils.isEmpty(mUrl)) {
            Uri uri = Uri.parse(mUrl);
            return ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())
                    || ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                    || "rawresource".equals(uri.getScheme());
        }
        return false;
    }

    /**
     * 初始化播放器
     */
    protected void initPlayer() {
        mMediaPlayer = mPlayerFactory.createPlayer(getContext());
        mMediaPlayer.setPlayerEventListener(this);
        setInitOptions();
        mMediaPlayer.initPlayer();
        setOptions();
    }

    /**
     * 初始化之前的配置项
     */
    protected void setInitOptions() {
    }

    /**
     * 初始化之后的配置项
     */
    protected void setOptions() {
        mMediaPlayer.setLooping(mIsLooping);
        float volume = mIsMute ? 0.0f : 1.0f;
        mMediaPlayer.setVolume(volume, volume);
    }

    /**
     * 初始化视频渲染View
     */
    protected void addDisplay() {
        if (mRenderView != null) {
            mPlayerContainer.removeView(mRenderView.getView());
            mRenderView.release();
        }
        mRenderView = mRenderViewFactory.createRenderView(getContext());
        mRenderView.attachToPlayer(mMediaPlayer);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        mPlayerContainer.addView(mRenderView.getView(), 0, params);
    }

    /**
     * 开始准备播放（直接播放）
     */
    protected void startPrepare(boolean reset) {
        if (reset) {
            mMediaPlayer.reset();
            //重新设置option，media player reset之后，option会失效
            setOptions();
        }
        startPrepareAfterReset();
    }

    /** 数据源已经 reset/设置完毕后，提交唯一的一次 prepare。 */
    protected void startPrepareAfterReset() {
        if (prepareDataSource()) {
            mMediaPlayer.prepareAsync();
            setPlayState(STATE_PREPARING);
            setPlayerState(isFullScreen() ? PLAYER_FULL_SCREEN : isTinyScreen() ? PLAYER_TINY_SCREEN : PLAYER_NORMAL);
        } else {
            // 已经创建 player/render 但数据源无效时不能停留在 IDLE，否则 release 会泄漏资源。
            setPlayState(STATE_ERROR);
        }
    }

    /**
     * 设置播放数据
     * @return 播放数据是否设置成功
     */
    protected boolean prepareDataSource() {
        if (mAssetFileDescriptor != null) {
            mMediaPlayer.setDataSource(mAssetFileDescriptor);
            return true;
        } else if (!TextUtils.isEmpty(mUrl)) {
            mMediaPlayer.setDataSource(mUrl, mHeaders);
            return true;
        }
        return false;
    }

    /**
     * 播放状态下开始播放
     */
    protected void startInPlaybackState() {
        if (mMediaPlayer == null) {
            return;
        }
        if (mCurrentPlayState != STATE_PREPARING
                && mCurrentPlayState != STATE_BUFFERING
                && mAudioFocusHelper != null
                && !mIsMute
                && !mAudioFocusHelper.requestFocus()) {
            // 用户意图保持为播放，焦点延迟获批时由 AudioFocusHelper 再次调用 start()。
            mPlayWhenReady = true;
            return;
        }
        mMediaPlayer.start();
        // PREPARING/BUFFERING 只提交 playWhenReady，真实播放状态由 ready/首帧事件决定。
        if (mCurrentPlayState != STATE_PREPARING && mCurrentPlayState != STATE_BUFFERING) {
            setPlayState(STATE_PLAYING);
        }
        mPlayerContainer.setKeepScreenOn(true);
        // 音频渐入
        if (mAudioFadeHelper != null) {
            mAudioFadeHelper.startFadeIn();
        }
    }

    /**
     * 暂停播放
     */
    @Override
    public void pause() {
        mPlayWhenReady = false;
        if (mMediaPlayer != null && (isInPlaybackState()
                || mCurrentPlayState == STATE_PREPARING
                || mCurrentPlayState == STATE_BUFFERING)) {
            mMediaPlayer.pause();
            setPlayState(STATE_PAUSED);
            if (mAudioFocusHelper != null && !isMute()) {
                mAudioFocusHelper.abandonFocus();
            }
            mPlayerContainer.setKeepScreenOn(false);
            // 暂停音频渐变
            if (mAudioFadeHelper != null) {
                mAudioFadeHelper.pause();
            }
        }
    }

    /**
     * 继续播放
     */
    public void resume() {
        mPlayWhenReady = true;
        if (mMediaPlayer == null) {
            return;
        }
        if (!mMediaPlayer.isPlaying()) {
            startInPlaybackState();
        } else if (mCurrentPlayState == STATE_PAUSED) {
            // native 已经在播，但 Java 状态还停在 PAUSED：startInPlaybackState() 在暂停态
            // 会刻意不派 STATE_PLAYING（等首帧事件），而首帧可能早已过去，状态会永久卡住，
            // 弹幕与中央 loading 圈都收不到恢复事件。这里只在明确 PAUSED 时收敛。
            //
            // 注意不能写成 mCurrentPlayState != STATE_PLAYING：Exo 在 STATE_BUFFERING 且
            // playWhenReady=true 时 isPlaying() 也返回 true，MPV 缓冲时 pause 属性同样是
            // false。若在缓冲中强行推 PLAYING，会把缓冲态盖掉，中央 loading 圈提前消失
            // 而画面其实还卡着——那是比"状态卡住"更难排查的问题。
            setPlayState(STATE_PLAYING);
            mPlayerContainer.setKeepScreenOn(true);
        }
    }

    /**
     * 释放播放器
     */
    public void release() {
        // IDLE 只表示"未在播"，不代表没有资源：prepareDataSource 失败后可能已有
        // player/render view，必须按真实资源判断，不能只看状态。
        if (mMediaPlayer != null || mRenderView != null || mAssetFileDescriptor != null) {
            // 必须在 player.release/null 之前取快照。getDuration() 依赖 player，
            // 旧顺序会让 duration 恒为 0，导致末尾进度不被清零。
            saveProgressSnapshot();
            mPlayWhenReady = false;

            if (mMediaPlayer != null) {
                mMediaPlayer.setPlayerEventListener(null);
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            mPlayerDetached = false;
            //释放renderView
            if (mRenderView != null) {
                mPlayerContainer.removeView(mRenderView.getView());
                mRenderView.release();
                mRenderView = null;
            }
            //释放Assets资源
            if (mAssetFileDescriptor != null) {
                try {
                    mAssetFileDescriptor.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mAssetFileDescriptor = null;
            }
            //关闭AudioFocus监听
            if (mAudioFocusHelper != null) {
                mAudioFocusHelper.abandonFocus();
                mAudioFocusHelper = null;
            }
            //取消音频渐变
            if (mAudioFadeHelper != null) {
                mAudioFadeHelper.cancel();
            }
            //关闭屏幕常亮
            mPlayerContainer.setKeepScreenOn(false);
            //重置播放进度
            mCurrentPosition = 0;
            //切换转态
            setPlayState(STATE_IDLE);
        }
    }

    /**
     * 取出当前 player 实例，供后台 Service 接管。
     * 与 {@link #release()} 不同：不销毁 player，不 null 化 mRenderView，
     * 只是把 player 引用拿走 + 停止视频解码（保留音频）。
     *
     * 调用后 BaseVideoView 处于"无 player 但有 render view"的中间态，
     * 必须随后调用 {@link #attachPlayerFromBackground} 恢复，或 {@link #release} 清理。
     *
     * @return 当前的 player 实例；如果已经没有 player 返回 null
     */
    public AbstractPlayer detachPlayerForBackground() {
        AbstractPlayer player = mMediaPlayer;
        if (player == null) {
            return null;
        }
        // 保存快照后再解绑，render view 不释放，回前台可重新绑定。
        saveProgressSnapshot();
        try {
            // MPV/Exo 的显式解绑语义由后端处理；null surface 在后端必须支持。
            player.setSurface(null);
        } catch (Exception e) {
            L.e("detach surface failed: " + e.getMessage());
        }
        mMediaPlayer = null;
        mPlayerDetached = true;
        mPlayerContainer.setKeepScreenOn(false);
        return player;
    }

    /**
     * 放回 player 实例，从前台 Service 取回后恢复显示。
     * 同时重新申请音频焦点：Service 在 returnPlayer 时已 abandon，
     * 只把开关设回 true 并不会真的持有焦点（F2-3）。
     */
    @SuppressWarnings("unchecked")
    public void attachPlayerFromBackground(AbstractPlayer player) {
        if (player == null) {
            return;
        }
        mMediaPlayer = (P) player;
        mPlayerDetached = false;
        mMediaPlayer.setPlayerEventListener(this);
        if (mRenderView != null) {
            mRenderView.attachToPlayer(mMediaPlayer);
        }
        if (mEnableAudioFocus) {
            if (mAudioFocusHelper == null) {
                mAudioFocusHelper = new AudioFocusHelper(this);
            }
            if (!mIsMute) {
                mAudioFocusHelper.requestFocus();
            }
        }
        if (mMediaPlayer.isPlaying()) {
            mPlayerContainer.setKeepScreenOn(true);
        }
    }

    /** 保存进度（不依赖当前状态，释放和 detach 共用）。 */
    protected void saveProgressSnapshot() {
        if (mProgressManager == null) {
            return;
        }
        long position = mCurrentPosition;
        long duration = 0;
        if (mMediaPlayer != null) {
            try {
                long livePosition = mMediaPlayer.getCurrentPosition();
                if (livePosition > 0) {
                    position = livePosition;
                }
                duration = mMediaPlayer.getDuration();
            } catch (Exception ignored) {
                // 释放中的后端可能拒绝查询，保留最后一次已知位置。
            }
        }
        if (position <= 0) {
            return;
        }
        long saved = xyz.doikki.videoplayer.util.ProgressUtils.computeSavedProgress(position, duration);
        L.d("saveProgress: pos=" + position + " dur=" + duration + " saved=" + saved);
        mProgressManager.saveProgress(mUrl, saved);
    }

    /**
     * 保存播放进度
     */
    protected void saveProgress() {
        if (mProgressManager != null && mCurrentPosition > 0) {
            saveProgressSnapshot();
        }
    }

    /**
     * 是否处于播放状态
     */
    protected boolean isInPlaybackState() {
        return mMediaPlayer != null
                && mCurrentPlayState != STATE_ERROR
                && mCurrentPlayState != STATE_IDLE
                && mCurrentPlayState != STATE_PREPARING
                && mCurrentPlayState != STATE_START_ABORT
                && mCurrentPlayState != STATE_PLAYBACK_COMPLETED;
    }

    /**
     * 是否处于未播放状态
     */
    protected boolean isInIdleState() {
        return mCurrentPlayState == STATE_IDLE;
    }

    /**
     * 播放中止状态
     */
    private boolean isInStartAbortState() {
        return mCurrentPlayState == STATE_START_ABORT;
    }

    /**
     * 重新播放
     *
     * @param resetPosition 是否从头开始播放
     */
    @Override
    public void replay(boolean resetPosition) {
        if (mMediaPlayer == null || mRenderView == null) {
            // player 已释放时不能 addDisplay(null)/reset()；有数据源则走完整起播。
            if (!TextUtils.isEmpty(mUrl) || mAssetFileDescriptor != null) {
                if (!resetPosition) {
                    mCurrentPosition = mProgressManager != null
                            ? mProgressManager.getSavedProgress(mUrl) : mCurrentPosition;
                }
                start();
            }
            return;
        }
        if (resetPosition) {
            mCurrentPosition = 0;
        }
        mPlayWhenReady = true;
        startPrepare(true);
    }

    /**
     * 获取视频总时长
     */
    @Override
    public long getDuration() {
        if (mMediaPlayer != null && mCurrentPlayState != STATE_ERROR && mCurrentPlayState != STATE_IDLE) {
            return mMediaPlayer.getDuration();
        }
        return 0;
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer != null && mCurrentPlayState != STATE_ERROR && mCurrentPlayState != STATE_IDLE) {
            mCurrentPosition = mMediaPlayer.getCurrentPosition();
            return mCurrentPosition;
        }
        return mCurrentPosition;
    }

    /**
     * 调整播放进度
     */
    @Override
    public void seekTo(long pos) {
        if (isInPlaybackState()) {
            mMediaPlayer.seekTo(pos);
            // Seek时重置渐出状态
            if (mAudioFadeHelper != null) {
                mAudioFadeHelper.onSeekTo();
            }
        }
    }

    /**
     * 是否处于播放状态
     */
    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mMediaPlayer.isPlaying();
    }

    /**
     * 获取当前缓冲百分比
     */
    @Override
    public int getBufferedPercentage() {
        return mMediaPlayer != null ? mMediaPlayer.getBufferedPercentage() : 0;
    }

    /**
     * 设置静音
     */
    @Override
    public void setMute(boolean isMute) {
        this.mIsMute = isMute;
        if (mMediaPlayer != null) {
            float volume = isMute ? 0.0f : 1.0f;
            mMediaPlayer.setVolume(volume, volume);
        }
    }

    /**
     * 是否处于静音状态
     */
    @Override
    public boolean isMute() {
        return mIsMute;
    }

    /**
     * 视频缓冲完毕，准备开始播放时回调
     */
    @Override
    public void onPrepared() {
        if (mPlayerDetached) {
            dispatchDetachedState(STATE_PREPARED);
            return;
        }
        setPlayState(STATE_PREPARED);
        boolean focusReady = mIsMute || mAudioFocusHelper == null || mAudioFocusHelper.requestFocus();
        if (mCurrentPosition > 0) {
            long duration = getDuration();
            if (xyz.doikki.videoplayer.util.ProgressUtils.isNearEnd(mCurrentPosition, duration)) {
                L.d("onPrepared: near end, restart from beginning (pos=" + mCurrentPosition + ", dur=" + duration + ")");
                mCurrentPosition = 0;
                if (mProgressManager != null) {
                    mProgressManager.saveProgress(mUrl, 0);
                }
            } else {
                seekTo(mCurrentPosition);
            }
        }
        if (!mPlayWhenReady || !focusReady) {
            // Exo/MPV 可能已经自行开始，必须把用户暂停/焦点未获批意图再提交给内核。
            if (mMediaPlayer != null) {
                mMediaPlayer.pause();
            }
            setPlayState(STATE_PAUSED);
            return;
        }
        // 显式提交起播：Exo 的 onRenderedFirstFrame 可能在 onPrepared 之前触发，
        // 此时状态会被 STATE_PREPARED 覆盖，必须在这里重新推进到 PLAYING，
        // 否则控制器因 isInPlaybackState() 排除 PREPARED 而无法弹出。
        startInPlaybackState();
        // 音频渐入
        if (mAudioFadeHelper != null) {
            mAudioFadeHelper.startFadeIn();
        }
    }

    /**
     * 播放信息回调，播放中的缓冲开始与结束，开始渲染视频第一帧，视频旋转信息
     */
    @Override
    public void onInfo(int what, int extra) {
        if (mPlayerDetached) {
            if (what == AbstractPlayer.MEDIA_INFO_BUFFERING_START) {
                dispatchDetachedState(STATE_BUFFERING);
            } else if (what == AbstractPlayer.MEDIA_INFO_BUFFERING_END) {
                dispatchDetachedState(STATE_BUFFERED);
            } else if (what == AbstractPlayer.MEDIA_INFO_RENDERING_START) {
                dispatchDetachedState(STATE_PLAYING);
            }
            return;
        }
        switch (what) {
            case AbstractPlayer.MEDIA_INFO_BUFFERING_START:
                setPlayState(STATE_BUFFERING);
                // 缓冲时暂停渐变
                if (mAudioFadeHelper != null) {
                    mAudioFadeHelper.onBufferingStart();
                }
                break;
            case AbstractPlayer.MEDIA_INFO_BUFFERING_END:
                // 「缓冲结束」语义必须独立派发：UI（StandardVideoController.isBuffering、
                // 弹幕）依赖 STATE_BUFFERED 来结束缓冲态。旧实现在 mPlayWhenReady=false
                // 时直接派 STATE_PAUSED，把 BUFFERED 整个吞掉，结果是中央 loading 圈
                // 永久残留、弹幕时钟停在缓冲开始处，而 native 视频仍在继续播。
                //
                // 注意：这里总是连续派两次状态（BUFFERED + PAUSED，或 BUFFERED + PLAYING），
                // 即两次全链 onPlayStateChanged。下游监听者对 STATE_BUFFERED 的处理必须
                // 幂等，且不得在该分支里自动恢复播放（如调用 start()），否则会在用户
                // 明确暂停的情况下短暂起播、再被 STATE_PAUSED 覆盖，产生状态闪烁。
                setPlayState(STATE_BUFFERED);
                if (!mPlayWhenReady) {
                    // 缓冲结束后再落实用户暂停意图，不覆盖上面的语义事件。
                    setPlayState(STATE_PAUSED);
                } else if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                    // 缓冲结束且内核确实在播：把状态推回 PLAYING。
                    //
                    // 不能只等 MEDIA_INFO_RENDERING_START：MPV 的首帧事件只在 FILE_LOADED
                    // 派一次，seek / 网络缓冲结束后不会再派；Exo 的 onRenderedFirstFrame
                    // 在 seek 后也不一定回调。实测两个内核拖进度条后都停在 STATE_BUFFERED，
                    // LongVideoActivity 的 STATE_PLAYING 分支（进度记录、后台切集交接）
                    // 因此永远不执行。
                    // isPlaying() 是"缓冲确实结束"的守卫（Exo 需 READY，MPV 需 pause=false），
                    // 用户暂停意图由上面的分支拦截，不会误推。
                    setPlayState(STATE_PLAYING);
                    mPlayerContainer.setKeepScreenOn(true);
                }
                // 缓冲结束时恢复渐变
                if (mAudioFadeHelper != null) {
                    mAudioFadeHelper.onBufferingEnd();
                }
                break;
            case AbstractPlayer.MEDIA_INFO_RENDERING_START:
                // 不得用首帧事件覆盖 BUFFERING/PAUSED；只更新屏幕常亮。
                if (mPlayWhenReady && mCurrentPlayState != STATE_BUFFERING) {
                    setPlayState(STATE_PLAYING);
                    mPlayerContainer.setKeepScreenOn(true);
                }
                break;
            case AbstractPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED:
                if (mRenderView != null) mRenderView.setVideoRotation(extra);
                break;
        }
    }

    /**
     * 视频播放出错回调
     */
    @Override
    public void onError() {
        if (mPlayerDetached) {
            dispatchDetachedState(STATE_ERROR);
            return;
        }
        mPlayerContainer.setKeepScreenOn(false);
        setPlayState(STATE_ERROR);
        // 播放错误时取消渐变
        if (mAudioFadeHelper != null) {
            mAudioFadeHelper.cancel();
        }
    }

    /**
     * 视频播放完成回调
     */
    @Override
    public void onCompletion() {
        if (mPlayerDetached) {
            dispatchDetachedState(STATE_PLAYBACK_COMPLETED);
            return;
        }
        mPlayerContainer.setKeepScreenOn(false);
        mCurrentPosition = 0;
        mPlayWhenReady = false;
        if (mProgressManager != null) {
            //播放完成，清除进度
            mProgressManager.saveProgress(mUrl, 0);
        }
        // 播放完成时确保渐出完成
        if (mAudioFadeHelper != null) {
            mAudioFadeHelper.onCompletion();
        }
        setPlayState(STATE_PLAYBACK_COMPLETED);
    }

    /**
     * detach 后 player 的终态只能通知监听者，不能修改 View 的状态字段，
     * 否则 Service 的真实状态与前台 View 会分叉。
     */
    private void dispatchDetachedState(int playState) {
        if (mOnStateChangeListeners != null) {
            for (OnStateChangeListener l : PlayerUtils.getSnapshot(mOnStateChangeListeners)) {
                if (l != null) {
                    l.onPlayStateChanged(playState);
                }
            }
        }
    }

    /**
     * 获取当前播放器的状态
     */
    public int getCurrentPlayerState() {
        return mCurrentPlayerState;
    }

    /**
     * 获取当前的播放状态
     */
    public int getCurrentPlayState() {
        return mCurrentPlayState;
    }

    /**
     * 获取缓冲速度
     */
    @Override
    public long getTcpSpeed() {
        return mMediaPlayer != null ? mMediaPlayer.getTcpSpeed() : 0;
    }

    /**
     * 获取已缓存的可播放时长（毫秒），供缓冲可视化使用
     */
    @Override
    public long getCachedDuration() {
        return mMediaPlayer != null ? mMediaPlayer.getCachedDuration() : 0;
    }

    /**
     * 设置播放速度
     */
    @Override
    public void setSpeed(float speed) {
        if (isInPlaybackState()) {
            mMediaPlayer.setSpeed(speed);
        }
    }

    @Override
    public float getSpeed() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getSpeed();
        }
        return 1f;
    }

    /**
     * 设置视频地址
     */
    public void setUrl(String url) {
        setUrl(url, null);
    }

    /**
     * 设置包含请求头信息的视频地址
     *
     * @param url     视频地址
     * @param headers 请求头
     */
    public void setUrl(String url, Map<String, String> headers) {
        // 换源前必须先按"旧 url"落盘进度：setUrl 之后 mUrl 已是新媒体，
        // 任何后续快照(release/startOrReplace)都会把旧位置写到新视频名下。
        if (mUrl != null && !mUrl.equals(url)) {
            saveProgressSnapshot();
        }
        mAssetFileDescriptor = null;
        mUrl = url;
        mHeaders = headers;
    }

    /**
     * 用于播放assets里面的视频文件
     */
    public void setAssetFileDescriptor(AssetFileDescriptor fd) {
        mUrl = null;
        this.mAssetFileDescriptor = fd;
    }

    /**
     * 一开始播放就seek到预先设置好的位置
     */
    public void skipPositionWhenPlay(int position) {
        this.mCurrentPosition = position;
    }

    /**
     * 设置音量 0.0f-1.0f 之间
     *
     * @param v1 左声道音量
     * @param v2 右声道音量
     */
    public void setVolume(float v1, float v2) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(v1, v2);
        }
    }

    /**
     * 设置进度管理器，用于保存播放进度
     */
    public void setProgressManager(@Nullable ProgressManager progressManager) {
        this.mProgressManager = progressManager;
    }

    /**
     * 循环播放， 默认不循环播放
     */
    public void setLooping(boolean looping) {
        mIsLooping = looping;
        if (mMediaPlayer != null) {
            mMediaPlayer.setLooping(looping);
        }
    }

    /**
     * 是否循环播放
     */
    public boolean isLooping() {
        return mIsLooping;
    }

    /**
     * 是否开启AudioFocus监听， 默认开启，用于监听其它地方是否获取音频焦点，如果有其它地方获取了
     * 音频焦点，此播放器将做出相应反应，具体实现见{@link AudioFocusHelper}
     */
    public void setEnableAudioFocus(boolean enableAudioFocus) {
        mEnableAudioFocus = enableAudioFocus;
    }

    /**
     * 是否启用音频渐入渐出，默认不开启
     * 开启后视频开播时音量渐入，视频接近结束时音量渐出
     */
    public void setEnableAudioFade(boolean enableAudioFade) {
        if (mAudioFadeHelper != null) {
            mAudioFadeHelper.setEnabled(enableAudioFade);
        }
    }

    /**
     * 设置音频渐入时长，单位ms，默认1000ms
     */
    public void setAudioFadeInDuration(int durationMs) {
        if (mAudioFadeHelper != null) {
            mAudioFadeHelper.setFadeInDuration(durationMs);
        }
    }

    /**
     * 设置音频渐出时长，单位ms，默认1000ms
     */
    public void setAudioFadeOutDuration(int durationMs) {
        if (mAudioFadeHelper != null) {
            mAudioFadeHelper.setFadeOutDuration(durationMs);
        }
    }

    /**
     * 自定义播放核心，继承{@link PlayerFactory}实现自己的播放核心
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setPlayerFactory(PlayerFactory playerFactory) {
        if (playerFactory == null) {
            throw new IllegalArgumentException("PlayerFactory can not be null!");
        }
        mPlayerFactory = playerFactory;
    }

    /**
     * 自定义RenderView，继承{@link RenderViewFactory}实现自己的RenderView
     */
    public void setRenderViewFactory(RenderViewFactory renderViewFactory) {
        if (renderViewFactory == null) {
            throw new IllegalArgumentException("RenderViewFactory can not be null!");
        }
        mRenderViewFactory = renderViewFactory;
    }

    /**
     * 进入全屏
     */
    @Override
    public void startFullScreen() {
        if (mIsFullScreen)
            return;

        ViewGroup decorView = getDecorView();
        if (decorView == null)
            return;

        mIsFullScreen = true;

        //隐藏NavigationBar和StatusBar
        hideSysBar(decorView);

        //从当前FrameLayout中移除播放器视图
        this.removeView(mPlayerContainer);
        //将播放器视图添加到DecorView中即实现了全屏
        decorView.addView(mPlayerContainer);

        setPlayerState(PLAYER_FULL_SCREEN);
    }

    @SuppressLint("ObsoleteSdkInt")
    protected void hideSysBar(ViewGroup decorView) {
        int uiOptions = decorView.getSystemUiVisibility();
        uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decorView.setSystemUiVisibility(uiOptions);
        getActivity().getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && mIsFullScreen) {
            //重新获得焦点时保持全屏状态
            hideSysBar(getDecorView());
        }
    }

    /**
     * 退出全屏
     */
    @Override
    public void stopFullScreen() {
        if (!mIsFullScreen)
            return;

        ViewGroup decorView = getDecorView();
        if (decorView == null)
            return;

        mIsFullScreen = false;

        //显示NavigationBar和StatusBar
        showSysBar(decorView);

        //把播放器视图从DecorView中移除并添加到当前FrameLayout中即退出了全屏
        decorView.removeView(mPlayerContainer);
        this.addView(mPlayerContainer);

        setPlayerState(PLAYER_NORMAL);
    }

    @SuppressLint("ObsoleteSdkInt")
    protected void showSysBar(ViewGroup decorView) {
        int uiOptions = decorView.getSystemUiVisibility();
        uiOptions &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            uiOptions &= ~View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decorView.setSystemUiVisibility(uiOptions);
        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    /**
     * 获取DecorView
     */
    protected ViewGroup getDecorView() {
        Activity activity = getActivity();
        if (activity == null) return null;
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    /**
     * 获取activity中的content view,其id为android.R.id.content
     */
    protected ViewGroup getContentView() {
        Activity activity = getActivity();
        if (activity == null) return null;
        return activity.findViewById(android.R.id.content);
    }

    /**
     * 获取Activity，优先通过Controller去获取Activity
     */
    protected Activity getActivity() {
        Activity activity;
        if (mVideoController != null) {
            activity = PlayerUtils.scanForActivity(mVideoController.getContext());
            if (activity == null) {
                activity = PlayerUtils.scanForActivity(getContext());
            }
        } else {
            activity = PlayerUtils.scanForActivity(getContext());
        }
        return activity;
    }

    /**
     * 判断是否处于全屏状态
     */
    @Override
    public boolean isFullScreen() {
        return mIsFullScreen;
    }

    /**
     * 开启小屏
     */
    public void startTinyScreen() {
        if (mIsTinyScreen) return;
        ViewGroup contentView = getContentView();
        if (contentView == null) return;
        this.removeView(mPlayerContainer);
        int width = mTinyScreenSize[0];
        if (width <= 0) {
            width = PlayerUtils.getScreenWidth(getContext(), false) / 2;
        }

        int height = mTinyScreenSize[1];
        if (height <= 0) {
            height = width * 9 / 16;
        }

        LayoutParams params = new LayoutParams(width, height);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        contentView.addView(mPlayerContainer, params);
        mIsTinyScreen = true;
        setPlayerState(PLAYER_TINY_SCREEN);
    }

    /**
     * 退出小屏
     */
    public void stopTinyScreen() {
        if (!mIsTinyScreen) return;

        ViewGroup contentView = getContentView();
        if (contentView == null) return;
        contentView.removeView(mPlayerContainer);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        this.addView(mPlayerContainer, params);

        mIsTinyScreen = false;
        setPlayerState(PLAYER_NORMAL);
    }

    public boolean isTinyScreen() {
        return mIsTinyScreen;
    }

    @Override
    public void onVideoSizeChanged(int videoWidth, int videoHeight) {
        if (mPlayerDetached) {
            return;
        }
        mVideoSize[0] = videoWidth;
        mVideoSize[1] = videoHeight;


        if (mRenderView != null) {
            mRenderView.setScaleType(mCurrentScreenScaleType);
            mRenderView.setVideoSize(videoWidth, videoHeight);
        }
    }

    /**
     * 设置控制器，传null表示移除控制器
     */
    public void setVideoController(@Nullable BaseVideoController mediaController) {
        mPlayerContainer.removeView(mVideoController);
        mVideoController = mediaController;
        if (mediaController != null) {
            mediaController.setMediaPlayer(this);
            LayoutParams params = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            mPlayerContainer.addView(mVideoController, params);
        }
    }

    /**
     * 设置视频比例
     */
    @Override
    public void setScreenScaleType(int screenScaleType) {
        mCurrentScreenScaleType = screenScaleType;
        if (mRenderView != null) {
            mRenderView.setScaleType(screenScaleType);
        }
    }

    /**
     * 设置镜像旋转，暂不支持SurfaceView
     */
    @Override
    public void setMirrorRotation(boolean enable) {
        if (mRenderView != null) {
            mRenderView.getView().setScaleX(enable ? -1 : 1);
        }
    }

    /**
     * 截图，暂不支持SurfaceView
     */
    @Override
    public Bitmap doScreenShot() {
        if (mRenderView != null) {
            return mRenderView.doScreenShot();
        }
        return null;
    }

    /**
     * 获取视频宽高,其中width: mVideoSize[0], height: mVideoSize[1]
     */
    @Override
    public int[] getVideoSize() {
        return mVideoSize;
    }

    /**
     * 旋转视频画面
     *
     * @param rotation 角度
     */
    @Override
    public void setRotation(float rotation) {
        if (mRenderView != null) {
            mRenderView.setVideoRotation((int) rotation);
        }
    }

    /**
     * 设置小屏的宽高
     *
     * @param tinyScreenSize 其中tinyScreenSize[0]是宽，tinyScreenSize[1]是高
     */
    public void setTinyScreenSize(int[] tinyScreenSize) {
        this.mTinyScreenSize = tinyScreenSize;
    }

    /**
     * 向Controller设置播放状态，用于控制Controller的ui展示
     */
    protected void setPlayState(int playState) {
        mCurrentPlayState = playState;
        if (mVideoController != null) {
            mVideoController.setPlayState(playState);
        }
        if (mOnStateChangeListeners != null) {
            for (OnStateChangeListener l : PlayerUtils.getSnapshot(mOnStateChangeListeners)) {
                if (l != null) {
                    l.onPlayStateChanged(playState);
                }
            }
        }
    }

    /**
     * 向Controller设置播放器状态，包含全屏状态和非全屏状态
     */
    protected void setPlayerState(int playerState) {
        mCurrentPlayerState = playerState;
        if (mVideoController != null) {
            mVideoController.setPlayerState(playerState);
        }
        if (mOnStateChangeListeners != null) {
            for (OnStateChangeListener l : PlayerUtils.getSnapshot(mOnStateChangeListeners)) {
                if (l != null) {
                    l.onPlayerStateChanged(playerState);
                }
            }
        }
    }

    /**
     * 播放状态改变监听器
     */
    public interface OnStateChangeListener {
        void onPlayerStateChanged(int playerState);
        void onPlayStateChanged(int playState);
    }

    /**
     * OnStateChangeListener的空实现。用的时候只需要重写需要的方法
     */
    public static class SimpleOnStateChangeListener implements OnStateChangeListener {
        @Override
        public void onPlayerStateChanged(int playerState) {}
        @Override
        public void onPlayStateChanged(int playState) {}
    }

    /**
     * 添加一个播放状态监听器，播放状态发生变化时将会调用。
     */
    public void addOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners == null) {
            mOnStateChangeListeners = new ArrayList<>();
        }
        mOnStateChangeListeners.add(listener);
    }

    /**
     * 移除某个播放状态监听
     */
    public void removeOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners != null) {
            mOnStateChangeListeners.remove(listener);
        }
    }

    /**
     * 设置一个播放状态监听器，播放状态发生变化时将会调用，
     * 如果你想同时设置多个监听器，推荐 {@link #addOnStateChangeListener(OnStateChangeListener)}。
     */
    public void setOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners == null) {
            mOnStateChangeListeners = new ArrayList<>();
        } else {
            mOnStateChangeListeners.clear();
        }
        mOnStateChangeListeners.add(listener);
    }

    /**
     * 移除所有播放状态监听
     */
    public void clearOnStateChangeListeners() {
        if (mOnStateChangeListeners != null) {
            mOnStateChangeListeners.clear();
        }
    }

    /**
     * 改变返回键逻辑，用于activity
     */
    public boolean onBackPressed() {
        return mVideoController != null && mVideoController.onBackPressed();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        L.d("onSaveInstanceState: " + mCurrentPosition);
        //activity切到后台后可能被系统回收，故在此处进行进度保存
        saveProgress();
        return super.onSaveInstanceState();
    }
}
