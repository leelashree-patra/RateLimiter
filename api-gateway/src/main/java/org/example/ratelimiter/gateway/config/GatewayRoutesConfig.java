package org.example.ratelimiter.gateway.config;

import java.util.function.Function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> browseOrdersRoute(
            @Qualifier("minuteWindowCounter") SlidingWindowCounter minuteWindowCounter,
            @Qualifier("dayWindowCounter") SlidingWindowCounter dayWindowCounter,
            @Value("${gateway.order-service.uri}") String orderServiceUri,
            @Value("${gateway.rate-limit.browse.per-minute.capacity:60}") long perMinuteCapacity,
            @Value("${gateway.rate-limit.browse.per-day.capacity:2000}") long perDayCapacity) {
        return slidingWindowRoute("browse", RequestPredicates.GET("/orders/**"), orderServiceUri,
                minuteWindowCounter, dayWindowCounter, perMinuteCapacity, perDayCapacity);
    }

    @Bean
    public RouterFunction<ServerResponse> checkoutOrdersRoute(
            @Qualifier("minuteWindowCounter") SlidingWindowCounter minuteWindowCounter,
            @Qualifier("dayWindowCounter") SlidingWindowCounter dayWindowCounter,
            @Value("${gateway.order-service.uri}") String orderServiceUri,
            @Value("${gateway.rate-limit.checkout.per-minute.capacity:5}") long perMinuteCapacity,
            @Value("${gateway.rate-limit.checkout.per-day.capacity:20}") long perDayCapacity) {
        return slidingWindowRoute("checkout", RequestPredicates.POST("/orders/**"), orderServiceUri,
                minuteWindowCounter, dayWindowCounter, perMinuteCapacity, perDayCapacity);
    }

    private RouterFunction<ServerResponse> slidingWindowRoute(String tier, RequestPredicate predicate,
            String orderServiceUri, SlidingWindowCounter minuteWindowCounter,
            SlidingWindowCounter dayWindowCounter, long perMinuteCapacity, long perDayCapacity) {
        Function<ServerRequest, String> keyResolver = RateLimitKeyResolver.forTier(tier);
        return GatewayRouterFunctions.route(tier + "_orders")
                .route(predicate, HandlerFunctions.http())
                .filter(FilterFunctions.uri(orderServiceUri))
                .filter((request, next) -> {
                    String key = keyResolver.apply(request);
                    SlidingWindowCounter.ConsumeResult minuteResult =
                            minuteWindowCounter.tryConsume(key, perMinuteCapacity);
                    if (!minuteResult.allowed()) {
                        return tooManyRequests(tier);
                    }
                    SlidingWindowCounter.ConsumeResult dayResult =
                            dayWindowCounter.tryConsume(key, perDayCapacity);
                    if (!dayResult.allowed()) {
                        minuteWindowCounter.release(key);
                        return tooManyRequests(tier);
                    }
                    ServerResponse response = next.handle(request);
                    long remaining = Math.min(minuteResult.remaining(), dayResult.remaining());
                    response.headers().add("X-RateLimit-" + tier + "-Remaining", String.valueOf(remaining));
                    return response;
                })
                .build();
    }

    private static ServerResponse tooManyRequests(String tier) throws Exception {
        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-" + tier + "-Remaining", "0")
                .build();
    }

}
