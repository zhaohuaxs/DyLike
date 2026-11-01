package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.datasource.rtmp.RtmpDataSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import me.lingci.lib.base.okhttp.OkUtil;
import me.lingci.lib.base.util.HttpUtil;
import me.lingci.lib.base.util.Log;
import me.lingci.lib.player.exo.smb.SmbDataSource;
import okhttp3.Call;
import okhttp3.Request;

@UnstableApi
public final class ExoMediaSourceHelper {

    private static volatile ExoMediaSourceHelper sInstance;

    private final String mUserAgent;
    private final Context mAppContext;
    private HttpDataSource.Factory mHttpDataSourceFactory;
    private OkHttpDataSource.Factory mOkHttpDataSourceFactory;
    private Cache mCache;

    private Boolean useOkhttp = false;

    @OptIn(markerClass = UnstableApi.class)
    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
        mUserAgent = Util.getUserAgent(mAppContext, mAppContext.getApplicationInfo().name);
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    @OptIn(markerClass = UnstableApi.class)
    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        Uri contentUri = Uri.parse(uri);
        if ("rtmp".equals(contentUri.getScheme())) {
            return new ProgressiveMediaSource.Factory(new RtmpDataSource.Factory())
                    .createMediaSource(MediaItem.fromUri(contentUri));
        } else if ("rtsp".equals(contentUri.getScheme())) {
            return new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri));
        } else if ("smb".equals(contentUri.getScheme())) {
            return new ProgressiveMediaSource.Factory(new SmbDataSource.Factory(headers))
                    .createMediaSource(MediaItem.fromUri(contentUri));
        }
        int contentType = inferContentType(uri);
        DataSource.Factory factory;
        if (isCache) {
            factory = getCacheDataSourceFactory();
        } else {
            factory = getDataSourceFactory();
        }
        if (useOkhttp) {
            if (mOkHttpDataSourceFactory != null) {
                setOkHeaders(headers);
            }
        } else {
            if (mHttpDataSourceFactory != null) {
                setHeaders(headers);
            }
        }
        if (uri.startsWith("/") && contentType == C.CONTENT_TYPE_OTHER) {
            contentUri = Uri.fromFile(new File(uri));
        }
        return switch (contentType) {
            case C.CONTENT_TYPE_DASH ->
                    new DashMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            case C.CONTENT_TYPE_HLS ->
                    new HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            default ->
                    new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
        };
    }

    /**
     * 为单个请求创建 DataSource。视频、外挂字幕和外挂音频必须共享同一套
     * headers/cache/SMB 语义，但不能共享可变的默认请求头工厂，否则会串鉴权。
     */
    @OptIn(markerClass = UnstableApi.class)
    public DataSource.Factory getDataSourceFactory(String uri, Map<String, String> headers, boolean isCache) {
        Uri parsed = Uri.parse(uri);
        if ("smb".equals(parsed.getScheme())) {
            return new SmbDataSource.Factory(headers == null ? null : new HashMap<>(headers));
        }

        Map<String, String> requestHeaders = headers == null
                ? new HashMap<>() : new HashMap<>(headers);
        String requestUserAgent = requestHeaders.remove("User-Agent");
        if (TextUtils.isEmpty(requestUserAgent)) {
            requestUserAgent = mUserAgent;
        }

        DataSource.Factory upstream;
        if (useOkhttp) {
            OkHttpDataSource.Factory okFactory = new OkHttpDataSource.Factory(new Call.Factory() {
                @NonNull
                @Override
                public Call newCall(@NonNull Request request) {
                    return OkUtil.INSTANCE.getUnsafeClient().newCall(request);
                }
            });
            okFactory.setUserAgent(requestUserAgent);
            okFactory.setDefaultRequestProperties(requestHeaders);
            upstream = new DefaultDataSource.Factory(mAppContext, okFactory);
        } else {
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(requestUserAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(requestHeaders);
            upstream = new DefaultDataSource.Factory(mAppContext, httpFactory);
        }

        if (!isCache) {
            return upstream;
        }
        if (mCache == null) {
            mCache = newCache();
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private int inferContentType(String fileName) {
        fileName = fileName.toLowerCase();
        if (fileName.contains(".mpd")) {
            return C.CONTENT_TYPE_DASH;
        } else if (fileName.contains(".m3u8")) {
            return C.CONTENT_TYPE_HLS;
        } else {
            return C.CONTENT_TYPE_OTHER;
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private DataSource.Factory getCacheDataSourceFactory() {
        if (mCache == null) {
            mCache = newCache();
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    @OptIn(markerClass = UnstableApi.class)
    private Cache newCache() {
        return new SimpleCache(
                new File(mAppContext.getExternalCacheDir(), "exo-video-cache"),//缓存目录
                new LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024),//缓存大小，默认512M，使用LRU算法实现
                new StandaloneDatabaseProvider(mAppContext));
    }

    /**
     * Returns a new DataSource factory.
     *
     * @return A new DataSource factory.
     */
    private DataSource.Factory getDataSourceFactory() {
        if (useOkhttp) {
            return new DefaultDataSource.Factory(mAppContext, getOkHttpDataSourceFactory());
        }
        return new DefaultDataSource.Factory(mAppContext, getHttpDataSourceFactory());
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    @OptIn(markerClass = UnstableApi.class)
    private DataSource.Factory getHttpDataSourceFactory() {
        if (mHttpDataSourceFactory == null) {
            mHttpDataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(mUserAgent)
                    .setAllowCrossProtocolRedirects(true);
        }
        return mHttpDataSourceFactory;
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    @OptIn(markerClass = UnstableApi.class)
    private DataSource.Factory getOkHttpDataSourceFactory() {
        if (mOkHttpDataSourceFactory == null) {
            mOkHttpDataSourceFactory = new OkHttpDataSource.Factory(new Call.Factory(){
                @NonNull
                @Override
                public Call newCall(@NonNull Request request) {
                    request.headers().forEach(pair ->
                            Log.d("okhttp " + pair.getFirst() + " " + pair.getSecond())
                    );
                    return OkUtil.INSTANCE.getUnsafeClient().newCall(request);
                }
            });
            mOkHttpDataSourceFactory.setUserAgent(mUserAgent);
        }
        return mOkHttpDataSourceFactory;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setHeaders(Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            // 如果发现用户通过header传递了UA，则强行将HttpDataSourceFactory里面的userAgent字段替换成用户的
            if (headers.containsKey("User-Agent")) {
                String value = headers.remove("User-Agent");
                if (!TextUtils.isEmpty(value)) {
                    try {
                        Field userAgentField = mHttpDataSourceFactory.getClass().getDeclaredField("userAgent");
                        userAgentField.setAccessible(true);
                        userAgentField.set(mHttpDataSourceFactory, value);
                    } catch (Exception e) {
                        //ignore
                    }
                }
            }
            Log.d(this, "setHeaders", headers);
            mHttpDataSourceFactory.setDefaultRequestProperties(headers);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setOkHeaders(Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            // 如果发现用户通过header传递了UA，则强行将HttpDataSourceFactory里面的userAgent字段替换成用户的
            if (headers.containsKey("User-Agent")) {
                String value = headers.remove("User-Agent");
                if (!TextUtils.isEmpty(value)) {
                    mOkHttpDataSourceFactory.setUserAgent(value);
                }
            }
            Log.d(this, "setHeaders", headers);
            mOkHttpDataSourceFactory.setDefaultRequestProperties(headers);
        } else {
            mOkHttpDataSourceFactory.setDefaultRequestProperties(new HashMap<>());
            mOkHttpDataSourceFactory.setUserAgent(HttpUtil.EDGE_UA);
        }
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }

    public void setUseOkhttp(Boolean used) {
        this.useOkhttp = used;
    }

}
