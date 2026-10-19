package org.example.ratelimiter.gateway.config;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
