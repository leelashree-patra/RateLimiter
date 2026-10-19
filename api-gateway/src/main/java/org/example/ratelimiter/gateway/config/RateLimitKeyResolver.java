package org.example.ratelimiter.gateway.config;

import java.util.function.Function;

import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.ServerRequest;

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
