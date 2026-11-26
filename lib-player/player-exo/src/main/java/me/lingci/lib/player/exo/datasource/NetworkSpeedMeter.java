package me.lingci.lib.player.exo.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@OptIn(markerClass = UnstableApi.class)
public final class NetworkSpeedMeter implements TransferListener {

    private static final long BUCKET_DURATION_MS = 1_000L;
    private static final long WINDOW_DURATION_MS = 2_000L;

    private final LongSupplier elapsedRealtimeMs;
    private final ArrayDeque<Bucket> buckets = new ArrayDeque<>();

    public NetworkSpeedMeter() {
        this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    NetworkSpeedMeter(LongSupplier elapsedRealtimeMs) {
        this.elapsedRealtimeMs = elapsedRealtimeMs;
    }

    public synchronized void recordBytes(long bytes) {
        if (bytes <= 0L) return;

        long now = elapsedRealtimeMs.getAsLong();
        long bucketStart = Math.floorDiv(now, BUCKET_DURATION_MS) * BUCKET_DURATION_MS;
        pruneBuckets(now);

        Bucket bucket = buckets.peekLast();
        if (bucket == null || bucket.startTimeMs != bucketStart) {
            bucket = new Bucket(bucketStart);
            buckets.addLast(bucket);
        }
        bucket.bytes += bytes;
    }

    public synchronized long getBytesPerSecond() {
        long now = elapsedRealtimeMs.getAsLong();
        pruneBuckets(now);

        long windowStartMs = now - WINDOW_DURATION_MS;
        double weightedBytes = 0.0;
        for (Bucket bucket : buckets) {
            long overlapStartMs = Math.max(bucket.startTimeMs, windowStartMs);
            long overlapEndMs = Math.min(bucket.startTimeMs + BUCKET_DURATION_MS, now);
            if (overlapEndMs <= overlapStartMs) continue;
            double overlapRatio = (overlapEndMs - overlapStartMs) / (double) BUCKET_DURATION_MS;
            weightedBytes += bucket.bytes * overlapRatio;
        }
        return Math.round(weightedBytes * 1_000.0 / WINDOW_DURATION_MS);
    }

    public synchronized void reset() {
        buckets.clear();
    }

    private void pruneBuckets(long now) {
        long windowStartMs = now - WINDOW_DURATION_MS;
        while (!buckets.isEmpty()
                && buckets.peekFirst().startTimeMs + BUCKET_DURATION_MS <= windowStartMs) {
            buckets.removeFirst();
        }
    }

    @Override
    public void onTransferInitializing(
            @NonNull DataSource source,
            @NonNull DataSpec dataSpec,
            boolean isNetwork
    ) {
    }

    @Override
    public void onTransferStart(
            @NonNull DataSource source,
            @NonNull DataSpec dataSpec,
            boolean isNetwork
    ) {
    }

    @Override
    public void onBytesTransferred(
            @NonNull DataSource source,
            @NonNull DataSpec dataSpec,
            boolean isNetwork,
            int bytes
    ) {
        if (isNetwork) recordBytes(bytes);
    }

    @Override
    public void onTransferEnd(
            @NonNull DataSource source,
            @NonNull DataSpec dataSpec,
            boolean isNetwork
    ) {
    }

    private static final class Bucket {
        final long startTimeMs;
        long bytes;

        Bucket(long startTimeMs) {
            this.startTimeMs = startTimeMs;
        }
    }
}
