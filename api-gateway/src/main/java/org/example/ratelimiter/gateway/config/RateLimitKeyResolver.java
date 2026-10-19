package org.example.ratelimiter.gateway.config;

import java.util.function.Function;

import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Resolves the Bucket4j bucket key for a request: an authenticated caller's {@code X-User-Id}
 * header when present (each user gets their own quota regardless of IP/NAT), falling back to
 * client IP for anonymous traffic.
 * <p>
 * Keys are scoped per tier ({@code tier + ":" + identity}) so that, say, the "browse" and
 * "checkout" tiers never share a bucket for the same client — if they did, whichever tier's
 * request created the bucket first would silently decide its capacity/period for both.
 */
final class RateLimitKeyResolver {

    private static final String USER_ID_HEADER = "X-User-Id";

    private RateLimitKeyResolver() {
    }

    static Function<ServerRequest, String> forTier(String tier) {
        return request -> tier + ":" + identity(request);
    }

    private static String identity(ServerRequest request) {
        String userId = request.headers().firstHeader(USER_ID_HEADER);
        if (StringUtils.hasText(userId)) {
            return "user:" + userId;
        }
        return "ip:" + request.remoteAddress()
                .map(address -> address.getAddress().getHostAddress())
                .orElse("unknown");
    }

}
