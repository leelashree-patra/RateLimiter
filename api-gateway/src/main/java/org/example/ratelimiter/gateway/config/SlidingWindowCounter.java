package org.example.ratelimiter.gateway.config;

import java.time.Duration;
import java.util.List;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Sliding-window-counter rate limiter, Redis-backed: time is split into fixed {@code windowSize}
 * buckets, and a request is judged against a weighted blend of the current and previous bucket's
 * counts ({@code previousCount * (1 - elapsedFraction) + currentCount}) rather than either a hard
 * reset at each boundary (fixed window's burst-doubling problem) or an exact per-request log.
 * <p>
 * Chosen over a sliding-window log specifically for large request volumes: this needs O(1) Redis
 * keys per client (exactly two counters), while a log needs one stored entry per request within
 * the window.
 * <p>
 * The whole check-and-increment runs as one atomic Lua script on the Redis server
 * ({@code EVAL}), so — unlike an in-process version — no client-side compare-and-set retry loop is
 * needed: Redis's single-threaded script execution already gives atomicity under concurrency.
 * {@code EXPIRE} on the current bucket mirrors Caffeine's {@code expireAfterWrite} auto-eviction.
 */
final class SlidingWindowCounter {

    private static final String SCRIPT = """
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local previous = tonumber(redis.call('GET', KEYS[2]) or '0')
            local elapsedFraction = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local ttlSeconds = tonumber(ARGV[3])
            local estimated = previous * (1 - elapsedFraction) + current
            if estimated >= capacity then
                return {0, 0}
            end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ttlSeconds)
            local remaining = capacity - estimated - 1
            if remaining < 0 then remaining = 0 end
            return {1, math.floor(remaining)}
            """;

    record ConsumeResult(boolean allowed, long remaining) {
    }

    private final RedisCommands<String, String> redis;
    private final long windowMillis;
    private final long ttlSeconds;

    SlidingWindowCounter(RedisCommands<String, String> redis, Duration windowSize) {
        this.redis = redis;
        this.windowMillis = windowSize.toMillis();
        this.ttlSeconds = windowSize.multipliedBy(2).toSeconds();
    }

    /**
     * Attempts to record one request against {@code key}, atomically on the Redis server.
     */
    ConsumeResult tryConsume(String key, long capacity) {
        long now = System.currentTimeMillis();
        long currentIndex = now / windowMillis;
        double elapsedFraction = (now % windowMillis) / (double) windowMillis;

        List<Object> result = redis.eval(SCRIPT, ScriptOutputType.MULTI,
                new String[] {bucketKey(key, currentIndex), bucketKey(key, currentIndex - 1)},
                String.valueOf(elapsedFraction), String.valueOf(capacity), String.valueOf(ttlSeconds));

        boolean allowed = ((Long) result.get(0)) == 1L;
        return new ConsumeResult(allowed, (Long) result.get(1));
    }

    /**
     * Undoes a {@link #tryConsume} (used when a different granularity's window rejects a request
     * after this one already allowed it).
     */
    void release(String key) {
        long currentIndex = System.currentTimeMillis() / windowMillis;
        redis.decr(bucketKey(key, currentIndex));
    }

    private static String bucketKey(String key, long windowIndex) {
        return key + ":" + windowIndex;
    }

}
