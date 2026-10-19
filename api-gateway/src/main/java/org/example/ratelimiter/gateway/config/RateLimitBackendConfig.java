package org.example.ratelimiter.gateway.config;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backing store for {@code Bucket4jFilterFunctions.rateLimit(...)}: tries Redis first so limits
 * are shared across every api-gateway instance, and falls back to an in-memory Caffeine bucket
 * (the original single-instance behavior) if Redis isn't reachable. No Docker/Redis install is
 * required to run this project — the fallback makes that optional, at the cost of per-instance
 * (not cross-instance) limits while running on it.
 */
@Configuration
public class RateLimitBackendConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitBackendConfig.class);

    @Bean
    public AsyncProxyManager<String> rateLimitProxyManager(
            @Value("${gateway.redis.host:localhost}") String host,
            @Value("${gateway.redis.port:6379}") int port,
            @Value("${gateway.redis.connect-timeout-ms:1000}") long connectTimeoutMs) {
        try {
            RedisClient redisClient = RedisClient.create(RedisURI.create(host, port));
            redisClient.setOptions(ClientOptions.builder()
                    .socketOptions(SocketOptions.builder()
                            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                            .build())
                    .build());
            StatefulRedisConnection<String, byte[]> connection = redisClient.connect(
                    RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            log.info("Rate limiting backed by Redis at {}:{}", host, port);
            return Bucket4jLettuce.casBasedBuilder(connection).build().asAsync();
        } catch (RuntimeException e) {
            log.warn("Redis unreachable at {}:{} ({}) - falling back to in-memory Caffeine rate "
                    + "limiting; limits will NOT be shared across gateway instances while on this "
                    + "fallback", host, port, e.getMessage());
            return new CaffeineProxyManager<String>(
                    Caffeine.newBuilder().maximumSize(10_000),
                    Duration.ofMinutes(5))
                    .asAsync();
        }
    }

}
