package org.example.ratelimiter.gateway.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Sliding-window-counter rate limiter: time is split into fixed {@code windowSize} buckets, and
 * a request is judged against a weighted blend of the current and previous bucket's counts
 * ({@code previousCount * (1 - elapsedFraction) + currentCount}) rather than either a hard reset
 * at each boundary (fixed window's burst-doubling problem) or an exact per-request log.
 * <p>
 * Chosen over a sliding-window log specifically for large request volumes: this needs O(1)
 * memory per key (exactly two counters), while a log needs one stored entry per request within
 * the window.
 * <p>
 * Caffeine's {@code expireAfterWrite} evicts stale buckets automatically — no manual cleanup.
 */
final class SlidingWindowCounter {

    record ConsumeResult(boolean allowed, long remaining) {
    }

    private final Cache<String, AtomicLong> windowCounts;
    private final long windowMillis;

    SlidingWindowCounter(Duration windowSize) {
        this.windowMillis = windowSize.toMillis();
        this.windowCounts = Caffeine.newBuilder()
                .expireAfterWrite(windowSize.multipliedBy(2))
                .maximumSize(100_000)
                .build();
    }

    /**
     * Attempts to record one request against {@code key}. Lock-free: retries the compare-and-set
     * if a concurrent request on the same key wins the race first.
     */
    ConsumeResult tryConsume(String key, long capacity) {
        long now = System.currentTimeMillis();
        long currentIndex = now / windowMillis;
        double elapsedFraction = (now % windowMillis) / (double) windowMillis;

        AtomicLong previousBucket = windowCounts.getIfPresent(bucketKey(key, currentIndex - 1));
        long previousCount = previousBucket == null ? 0 : previousBucket.get();
        AtomicLong currentBucket = windowCounts.get(bucketKey(key, currentIndex), k -> new AtomicLong());

        while (true) {
            long currentCount = currentBucket.get();
            double estimated = previousCount * (1 - elapsedFraction) + currentCount;
            if (estimated >= capacity) {
                return new ConsumeResult(false, 0);
            }
            if (currentBucket.compareAndSet(currentCount, currentCount + 1)) {
                return new ConsumeResult(true, Math.max(0, capacity - Math.round(estimated) - 1));
            }
            // lost the CAS race to a concurrent request on the same key - recompute and retry
        }
    }

    /**
     * Undoes a {@link #tryConsume} (used when a different granularity's window rejects a
     * request after this one already allowed it). Known approximation: between a successful
     * {@code tryConsume} and this {@code release}, a concurrent request on the same key could
     * briefly observe a slightly inflated count - acceptable since the whole algorithm is already
     * an estimate, not an exact count.
     */
    void release(String key) {
        long currentIndex = System.currentTimeMillis() / windowMillis;
        AtomicLong currentBucket = windowCounts.getIfPresent(bucketKey(key, currentIndex));
        if (currentBucket != null) {
            currentBucket.decrementAndGet();
        }
    }

    private static String bucketKey(String key, long windowIndex) {
        return key + ":" + windowIndex;
    }

}
