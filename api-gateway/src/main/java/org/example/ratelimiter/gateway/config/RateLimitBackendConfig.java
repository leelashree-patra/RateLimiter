package org.example.ratelimiter.gateway.config;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backing store for {@code Bucket4jFilterFunctions.rateLimit(...)}: an in-memory Caffeine bucket,
 * shared by every tier in {@link GatewayRoutesConfig} (keys are tier-scoped by
 * {@link RateLimitKeyResolver}, so one Caffeine cache safely holds all of them). Buckets live in
 * this JVM only, so limits are per gateway instance, not shared across replicas — swapping this
 * one bean for a distributed {@code AsyncProxyManager} (e.g. Redis via
 * {@code bucket4j_jdk17-lettuce}) is the only change needed to make them cluster-wide; nothing
 * in {@link GatewayRoutesConfig} or {@link RateLimitKeyResolver} would need to change.
 */
@Configuration
public class RateLimitBackendConfig {

    @Bean
    public AsyncProxyManager<String> rateLimitProxyManager() {
        return new CaffeineProxyManager<String>(
                Caffeine.newBuilder().maximumSize(10_000),
                Duration.ofMinutes(5))
                .asAsync();
    }

}
