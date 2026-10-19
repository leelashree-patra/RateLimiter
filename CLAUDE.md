# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Use the Maven wrapper (`./mvnw` in Git Bash, `mvnw.cmd` in PowerShell) from the repo root — it drives
the whole reactor. The wrapper is configured with `distributionType=only-script`, so it fetches Maven
3.10.0 itself; no local Maven install is needed.

```bash
./mvnw test                                                          # all tests, both modules
./mvnw -pl api-gateway spring-boot:run                               # run just the gateway (port 8080)
./mvnw -pl order-service spring-boot:run                             # run just order-service (port 8081)
./mvnw -pl api-gateway test -Dtest=ApiGatewayApplicationTests        # single test class, one module
./mvnw -pl api-gateway test -Dtest=ApiGatewayApplicationTests#contextLoads   # single test method
./mvnw package                                                      # executable jars into each module's target/
./mvnw -pl api-gateway spring-boot:build-image                      # OCI image for one module via Cloud Native Buildpacks
```

Run both services together to exercise the whole thing (two terminals, or background one):
`order-service` must be up first since `api-gateway` proxies to it. With DevTools on the classpath,
`spring-boot:run` live-reloads on recompile — but a **new dependency** (pom.xml change) requires
killing and restarting the process; DevTools' restart classloader does not pick up new jars.

No linter or static-analysis plugin is configured.

## Architecture

Maven multi-module reactor, Spring Boot 4.1.1 / Java 17, root `pom.xml` is `packaging=pom` with
`<modules>api-gateway, order-service</modules>` and the shared `spring-cloud-dependencies` BOM
(2025.1.3) in its `<dependencyManagement>`.

- **`api-gateway`** — the gateway. Routes `/orders/**` to `order-service` in two sensitivity tiers,
  each rate-limited independently (below). `ApiGatewayApplication` (boot entrypoint) sits in
  `org.example.ratelimiter.gateway`; `GatewayRoutesConfig`, `RateLimitBackendConfig`,
  `RateLimitKeyResolver`, and `SlidingWindowCounter` are in its `.config` sub-package.
- **`order-service`** — the backend being proxied to (`org.example.orderservice`), split into
  `entity` (`Order` record), `store` (`OrderStore`, in-memory `ConcurrentHashMap`, no persistence
  dependency), and `controller` (`OrderController`: `GET /orders`, `GET /orders/{id}`, `POST /orders`).

### `api-gateway` is the WebMVC gateway variant, not the reactive one

It depends on `spring-cloud-starter-gateway-server-webmvc`, the servlet/blocking flavor of Spring
Cloud Gateway. Routing and filtering code lives under `org.springframework.cloud.gateway.server.mvc.*`,
built on Spring MVC's functional endpoints — **not** `RouteLocatorBuilder`, `GatewayFilter`,
`GlobalFilter`, `KeyResolver`, or anything returning `Mono`/`Flux`. Most Spring Cloud Gateway material
online targets the reactive server and will not compile here.

`GatewayRoutesConfig.slidingWindowRoute` shows the pattern used throughout this module:
`GatewayRouterFunctions.route(name).route(predicate, HandlerFunctions.http())` builds the
`RouterFunction<ServerResponse>` bean; `HandlerFunctions.http()` is a generic proxying handler that
reads its target URI from a request attribute, so it's always paired with a
`FilterFunctions.uri(...)` filter that sets that attribute — `http()` alone has nowhere to proxy to.

Declarative (property-based) routes would use the `spring.cloud.gateway.server.webmvc.routes[*]`
prefix (note `server.webmvc`, not the older `spring.cloud.gateway.routes`), but this project only uses
the Java `RouterFunction` bean style — stay consistent with that rather than mixing in properties-based
routes.

### Rate limiting: a hand-rolled sliding-window counter, two tiers, two granularities each

There's no rate-limiting *library* in this project (Bucket4j was removed — see below); the limiter
is `SlidingWindowCounter`, a small hand-written class, applied from a plain `HandlerFilterFunction`
lambda in `GatewayRoutesConfig.slidingWindowRoute`. It's used **twice**, once per route —
`browseOrdersRoute` (`GET /orders/**`, loose: `gateway.rate-limit.browse.*`) and
`checkoutOrdersRoute` (`POST /orders/**`, strict: `gateway.rate-limit.checkout.*`) — because real
e-commerce APIs don't apply one uniform limit everywhere; placing an order is far more sensitive
than browsing them.

**Why sliding window *counter*, not a sliding window *log*.** A sliding window log (store every
request's timestamp, count how many fall in the trailing period) is exact but needs
O(requests-in-window) memory per client — up to 2000 stored entries per client for `browse`'s
2000/day limit, growing with traffic. `SlidingWindowCounter` instead splits time into fixed
`windowSize` buckets and estimates the trailing count as a weighted blend of the current and
previous bucket (`previousCount * (1 - elapsedFraction) + currentCount`) — exactly two `AtomicLong`
entries per key, regardless of request volume. This is the standard answer to "sliding window at
scale," chosen specifically because the ask was sliding window *for large request volumes*. It's
also why a hard fixed window (reset to zero at each boundary) wasn't used: that lets a client do up
to 2x its limit by timing a burst across a boundary, which the weighted blend avoids.

**Two granularities per tier, checked as AND, on two separate counters — not one atomic bucket.**
`RateLimitBackendConfig` exposes `minuteWindowCounter`/`dayWindowCounter` beans (one
`SlidingWindowCounter` per granularity, `@Qualifier`-disambiguated since both are the same type).
`slidingWindowRoute` calls `minuteWindowCounter.tryConsume(key, perMinuteCapacity)` first; only if
that allows the request does it call `dayWindowCounter.tryConsume(...)`. If the day check then
rejects, `minuteWindowCounter.release(key)` gives the minute token back. **Known approximation**:
between that `tryConsume` and the matching `release`, a concurrent request on the same key could
briefly see a slightly inflated minute count — acceptable since the algorithm is already an
estimate, not an exact count, and avoids building a cross-counter transactional check for what
Bucket4j got "for free" by keeping both bandwidths in one atomic bucket.

**Keys are tier-scoped, not just client-scoped**, same as before: `RateLimitKeyResolver.forTier(tier)`
resolves each request's identity (`X-User-Id` header when sent, else client IP) and prefixes it with
the tier name (`"browse:ip:1.2.3.4"` vs `"checkout:ip:1.2.3.4"`), so one shared pair of counter beans
safely serves every tier — `browse` and `checkout` traffic for the same client never share a bucket.

**Concurrency**: `tryConsume` is lock-free — a `compareAndSet` loop that re-reads and retries if a
concurrent request on the same key wins the race first, so no request is silently dropped or double
counted under contention. Verified with parallel bursts (`xargs -P`) against a single fresh key:
90 concurrent requests at a 60/window cap landed exactly 60 `200`s and 30 `429`s; 10 concurrent
requests at a 5/window cap landed exactly 5 and 5.

**Bucket4j was removed from this project** (it's still on the earlier
`feature/redis-sliding-window-rate-limit` branch, and recoverable from git history on `main`, if a
library-backed token-bucket approach is ever wanted again) — `com.bucket4j:*` dependencies are gone
from `api-gateway/pom.xml`; only `com.github.ben-manes.caffeine:caffeine` remains, now used directly
by `SlidingWindowCounter` for its per-key counter storage (`expireAfterWrite` auto-evicts stale
buckets, same pattern the old Caffeine-backed Bucket4j proxy manager used).

Verified manually: 6 rapid `POST /orders` from one client succeed 5 times then 429 (checkout's
5/minute), while `GET /orders` from that same client right after is unaffected (still 200s against
its own 60/minute counters) — proving both the dual-granularity limit and the tier isolation.
Response headers are tier-specific too (`X-RateLimit-browse-Remaining`,
`X-RateLimit-checkout-Remaining`), decreasing across successive allowed requests and reading `0` on
a 429.
