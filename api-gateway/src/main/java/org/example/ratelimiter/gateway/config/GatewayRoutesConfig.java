package org.example.ratelimiter.gateway.config;

import java.time.Duration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Routes {@code /orders/**} through to order-service, rate-limited per client by the gateway's
 * built-in Bucket4j filter. Two tiers apply, mapped onto order-service by sensitivity rather than
 * uniformly: a loose limit for browsing ({@code GET}) and a strict one for placing an order
 * ({@code POST}, the checkout-equivalent action here) — real e-commerce APIs generally don't rate
 * limit every route the same way. Each tier is its own Bucket4j bucket (see
 * {@link RateLimitKeyResolver}), so exhausting one doesn't affect the other; within a tier, two
 * bandwidths apply at once (a short per-minute cap and a longer per-day quota) and a request is
 * let through only if both still have capacity.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> browseOrdersRoute(
            @Value("${gateway.order-service.uri}") String orderServiceUri,
            @Value("${gateway.rate-limit.browse.per-minute.capacity:60}") long perMinuteCapacity,
            @Value("${gateway.rate-limit.browse.per-day.capacity:2000}") long perDayCapacity) {
        return tieredRoute("browse", RequestPredicates.GET("/orders/**"), orderServiceUri,
                perMinuteCapacity, perDayCapacity);
    }

    @Bean
    public RouterFunction<ServerResponse> checkoutOrdersRoute(
            @Value("${gateway.order-service.uri}") String orderServiceUri,
            @Value("${gateway.rate-limit.checkout.per-minute.capacity:5}") long perMinuteCapacity,
            @Value("${gateway.rate-limit.checkout.per-day.capacity:20}") long perDayCapacity) {
        return tieredRoute("checkout", RequestPredicates.POST("/orders/**"), orderServiceUri,
                perMinuteCapacity, perDayCapacity);
    }

    private RouterFunction<ServerResponse> tieredRoute(String tier, RequestPredicate predicate,
            String orderServiceUri, long perMinuteCapacity, long perDayCapacity) {
        return GatewayRouterFunctions.route(tier + "_orders")
                .route(predicate, HandlerFunctions.http())
                .filter(FilterFunctions.uri(orderServiceUri))
                .filter(Bucket4jFilterFunctions.rateLimit(config -> {
                    config.setKeyResolver(RateLimitKeyResolver.forTier(tier));
                    config.setHeaderName("X-RateLimit-" + tier + "-Remaining");
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
