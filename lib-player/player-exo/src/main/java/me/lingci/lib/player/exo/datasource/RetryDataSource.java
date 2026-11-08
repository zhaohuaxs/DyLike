package me.lingci.lib.player.exo.datasource;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import me.lingci.lib.base.util.Log;

/**
 * 带重试的 {@link DataSource} 包装层。
 *
 * <p>ExoPlayer 自带的 {@code LoadErrorHandlingPolicy} 也会重试，但它走的是「Loader 层报错 → 播放器
 * 进入 BUFFERING/ERROR → 重新 prepare」这条路径，副作用是：UI 会闪一下出错状态、{@code onPlayerError}
 * 有可能被提前触发、并且每次重试都要重建 sample 队列。对于 500/429/连接被重置这类瞬时错误，
 * 更好的做法是在最靠近网络的地方就地重试，让上层完全无感。
 *
 * <p>本类就做这一件事，覆盖两条失败路径：
 * <ul>
 *   <li>{@link #open(DataSpec)}：起播 / seek 时的建连、TLS、HTTP 状态码失败；</li>
 *   <li>{@link #read}：播放中途连接被对端掐断（服务端 4Mbps 场景下很常见）。</li>
 * </ul>
 * 中途重连会把 {@link DataSpec} 的 Range 起点按「已读字节数」往前推，所以不会重复下载、
 * 也不会丢掉进度，重回放器看来只是这一次 read 变慢了。
 *
 * <p>只对 HTTP(S) 生效 —— 调用方应当只包装 HTTP 工厂，本地文件/资产没必要付出重试代价。
 */
@UnstableApi
public final class RetryDataSource implements DataSource {

    private static final String TAG = "ExoRetry";

    /** 总尝试次数（含首次）。4 次 ≈ 最坏多等 2.1s，比让用户看到「播放出错」划算。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 4;

    /** 退避基数：300 → 600 → 1200 → ...（封顶 {@link #BACKOFF_MAX_MS}）。 */
    private static final long BACKOFF_BASE_MS = 300L;
    private static final long BACKOFF_MAX_MS = 2_000L;

    private final DataSource upstream;
    private final int maxAttempts;

    /** 播放器传入的原始 DataSpec；{@code null} 表示当前未 open。 */
    @Nullable
    private DataSpec baseSpec;
    /** 本次 open 生命周期内已成功吐给上层的字节数，用于重连时推进 Range 起点。 */
    private long consumedBytes;
    private boolean opened;

    public RetryDataSource(@NonNull DataSource upstream, int maxAttempts) {
        this.upstream = upstream;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        baseSpec = dataSpec;
        consumedBytes = 0;
        opened = false;

        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                long length = upstream.open(dataSpec);
                opened = true;
                return length;
            } catch (IOException e) {
                opened = false;
                lastError = e;
                closeQuietly();
                if (!shouldRetry(e, attempt)) {
                    throw e;
                }
                long delay = backoffMs(attempt);
                Log.d(TAG, "open failed, attempt " + attempt + "/" + maxAttempts
                        + ", retry in " + delay + "ms: " + describe(e));
                sleep(delay);
            }
        }
        throw lastError;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (baseSpec == null) {
            throw new IllegalStateException("read() called before open()");
        }
        int attempt = 1;
        while (true) {
            try {
                if (!opened) {
                    // 上一次 read 失败后连接已关闭，先按已读进度重新建连
                    reopen();
                    opened = true;
                }
                int read = upstream.read(buffer, offset, length);
                if (read > 0) {
                    consumedBytes += read;
                }
                return read;
            } catch (IOException e) {
                opened = false;
                closeQuietly();
                // 已经读完该 spec 声明的长度时，重连没有意义
                boolean noMoreData = baseSpec.length != C.LENGTH_UNSET
                        && consumedBytes >= baseSpec.length;
                if (noMoreData || !shouldRetry(e, attempt)) {
                    throw e;
                }
                long delay = backoffMs(attempt);
                Log.d(TAG, "read failed at " + consumedBytes + "B, attempt " + attempt
                        + "/" + maxAttempts + ", retry in " + delay + "ms: " + describe(e));
                sleep(delay);
                attempt++;
            }
        }
    }

    @Override
    @Nullable
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        // 交给真正发请求的那一层，CacheDataSource 等会读它
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        opened = false;
        baseSpec = null;
        consumedBytes = 0;
        upstream.close();
    }

    /** 按已读字节数推进 Range 起点后重新 open。 */
    private void reopen() throws IOException {
        DataSpec spec = baseSpec;
        if (spec == null) {
            throw new IllegalStateException("reopen() without spec");
        }
        DataSpec retrySpec = consumedBytes == 0 ? spec : spec.subrange(consumedBytes);
        upstream.open(retrySpec);
    }

    /**
     * 哪些错误值得重试。
     *
     * <p>原则：只重试「换一次可能就好了」的瞬时错误；鉴权/不存在/参数错误这类重试多少次都一样，
     * 直接抛给上层，别让用户白等。
     */
    private boolean shouldRetry(IOException e, int attempt) {
        if (attempt >= maxAttempts) {
            return false;
        }
        // 播放器主动取消（stop / seek）不该被当成故障重试
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (e instanceof InterruptedIOException && !(e instanceof SocketTimeoutException)) {
            return false;
        }
        if (e instanceof HttpDataSource.InvalidResponseCodeException) {
            int code = ((HttpDataSource.InvalidResponseCodeException) e).responseCode;
            // 5xx 服务端瞬时故障；408 请求超时；429 被限流（本项目 WebDAV 源上很常见）
            return code >= 500 || code == 408 || code == 429;
        }
        if (e instanceof HttpDataSource.HttpDataSourceException) {
            int type = ((HttpDataSource.HttpDataSourceException) e).type;
            return type == HttpDataSource.HttpDataSourceException.TYPE_OPEN
                    || type == HttpDataSource.HttpDataSourceException.TYPE_READ;
        }
        // 其余 IOException：连接重置、读超时、对端直接断开等，都可以再试
        return true;
    }

    private static long backoffMs(int attempt) {
        long delay = BACKOFF_BASE_MS << Math.min(attempt - 1, 3);
        return Math.min(delay, BACKOFF_MAX_MS);
    }

    private static String describe(IOException e) {
        if (e instanceof HttpDataSource.InvalidResponseCodeException) {
            HttpDataSource.InvalidResponseCodeException ex =
                    (HttpDataSource.InvalidResponseCodeException) e;
            return "http " + ex.responseCode + " " + ex.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private void closeQuietly() {
        try {
            upstream.close();
        } catch (Exception ignored) {
            // 关闭失败不影响重试
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 包装一个 HTTP 层的 DataSource.Factory，使每次 createDataSource() 都带上重试。 */
    public static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstreamFactory;
        private final int maxAttempts;

        public Factory(@NonNull DataSource.Factory upstreamFactory) {
            this(upstreamFactory, DEFAULT_MAX_ATTEMPTS);
        }

        public Factory(@NonNull DataSource.Factory upstreamFactory, int maxAttempts) {
            this.upstreamFactory = upstreamFactory;
            this.maxAttempts = maxAttempts;
        }

        @Override
        @NonNull
        public DataSource createDataSource() {
            return new RetryDataSource(upstreamFactory.createDataSource(), maxAttempts);
        }
    }
}
