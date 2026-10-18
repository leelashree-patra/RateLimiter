package org.example.ratelimiter.gateway.config;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Routes {@code /orders/**} through to order-service, rate-limited per client IP by the
 * gateway's built-in Bucket4j filter. Two limits apply at once (a single Bucket4j bucket with
 * two bandwidths): a short per-minute cap and a longer per-day quota. A request is let through
 * only if both bandwidths still have capacity.
 */
@Configuration
public class GatewayRoutesConfig {

    /**
     * Bucket4jFilterFunctions.rateLimit(...) resolves its bucket storage by looking up an
     * {@code AsyncProxyManager} bean from the application context at request time. bucket4j-core
     * ships no concrete implementation (only abstract scaffolding for distributed backends), so
     * an in-memory Caffeine-backed one is wired here; buckets live in this JVM only, matching the
     * single-instance scope of this demo.
     */
    @Bean
    public AsyncProxyManager<String> rateLimitProxyManager() {
        return new CaffeineProxyManager<String>(
                Caffeine.newBuilder().maximumSize(10_000),
                Duration.ofMinutes(5))
                .asAsync();
    }

    @Bean
    public RouterFunction<ServerResponse> orderServiceRoute(
            @Value("${gateway.order-service.uri}") String orderServiceUri,
            @Value("${gateway.rate-limit.per-minute.capacity:5}") long perMinuteCapacity,
            @Value("${gateway.rate-limit.per-day.capacity:20}") long perDayCapacity) {
        return GatewayRouterFunctions.route("order_service")
                .route(RequestPredicates.path("/orders/**"), HandlerFunctions.http())
                .filter(FilterFunctions.uri(orderServiceUri))
                .filter(Bucket4jFilterFunctions.rateLimit(config -> {
                    config.setKeyResolver(request -> request.remoteAddress()
                            .map(address -> address.getAddress().getHostAddress())
                            .orElse("unknown"));
                    // setConfigurationBuilder overrides the single-bandwidth default
                    // (capacity/period fields) with a bucket that carries both limits at once.
                    config.setConfigurationBuilder(ignored -> BucketConfiguration.builder()
                            .addLimit(Bandwidth.classic(perMinuteCapacity,
                                    Refill.greedy(perMinuteCapacity, Duration.ofMinutes(1))))
                            .addLimit(Bandwidth.classic(perDayCapacity,
                                    Refill.greedy(perDayCapacity, Duration.ofDays(1))))
                            .build());
                }))
                .build();
    }

}
