package org.example.ratelimiter.gateway.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitBackendConfig {

    @Bean
    public SlidingWindowCounter minuteWindowCounter() {
        return new SlidingWindowCounter(Duration.ofMinutes(1));
    }

    @Bean
    public SlidingWindowCounter dayWindowCounter() {
        return new SlidingWindowCounter(Duration.ofDays(1));
    }

}
