package org.example.ratelimiter.gateway.config;

import java.io.IOException;
import java.time.Duration;

import com.github.fppt.jedismock.RedisServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backing store for {@link SlidingWindowCounter}: Redis, by default an embedded
 * (in-process, pure-JVM) instance so this project needs no Docker or Redis install to run.
 * <p>
 * {@code gateway.redis.embedded=false} opts out and connects to a real external Redis at
 * {@code gateway.redis.host}/{@code gateway.redis.port} instead — the embedded server is for
 * local dev/demo convenience; it's a testing reimplementation of the Redis protocol
 * ({@code jedis-mock}), not production-grade.
 */
@Configuration
public class RateLimitBackendConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitBackendConfig.class);

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "gateway.redis.embedded", havingValue = "true", matchIfMissing = true)
    public RedisServer embeddedRedisServer() throws IOException {
        RedisServer server = RedisServer.newRedisServer().start();
        log.info("Embedded Redis (jedis-mock) started on {}:{}", server.getHost(), server.getBindPort());
        return server;
    }

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(ObjectProvider<RedisServer> embeddedRedisServer,
            @Value("${gateway.redis.host:localhost}") String host,
            @Value("${gateway.redis.port:6379}") int port) {
        RedisServer embedded = embeddedRedisServer.getIfAvailable();
        String resolvedHost = embedded != null ? embedded.getHost() : host;
        int resolvedPort = embedded != null ? embedded.getBindPort() : port;
        log.info("Rate limiting backed by Redis at {}:{}{}", resolvedHost, resolvedPort,
                embedded != null ? " (embedded)" : "");
        return RedisClient.create(RedisURI.create(resolvedHost, resolvedPort));
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    public SlidingWindowCounter minuteWindowCounter(StatefulRedisConnection<String, String> connection) {
        return new SlidingWindowCounter(connection.sync(), Duration.ofMinutes(1));
    }

    @Bean
    public SlidingWindowCounter dayWindowCounter(StatefulRedisConnection<String, String> connection) {
        return new SlidingWindowCounter(connection.sync(), Duration.ofDays(1));
    }

}
