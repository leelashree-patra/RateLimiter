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
  `org.example.ratelimiter.gateway`; `GatewayRoutesConfig`, `RateLimitBackendConfig`, and
  `RateLimitKeyResolver` are in its `.config` sub-package.
- **`order-service`** — the backend being proxied to (`org.example.orderservice`), split into
  `entity` (`Order` record), `store` (`OrderStore`, in-memory `ConcurrentHashMap`, no persistence
  dependency), and `controller` (`OrderController`: `GET /orders`, `GET /orders/{id}`, `POST /orders`).

### `api-gateway` is the WebMVC gateway variant, not the reactive one

It depends on `spring-cloud-starter-gateway-server-webmvc`, the servlet/blocking flavor of Spring
Cloud Gateway. Routing and filtering code lives under `org.springframework.cloud.gateway.server.mvc.*`,
built on Spring MVC's functional endpoints — **not** `RouteLocatorBuilder`, `GatewayFilter`,
`GlobalFilter`, `KeyResolver`, or anything returning `Mono`/`Flux`. Most Spring Cloud Gateway material
online targets the reactive server and will not compile here.

`GatewayRoutesConfig.tieredRoute` shows the pattern used throughout this module:
`GatewayRouterFunctions.route(name).route(predicate, HandlerFunctions.http())` builds the
`RouterFunction<ServerResponse>` bean; `HandlerFunctions.http()` is a generic proxying handler that
reads its target URI from a request attribute, so it's always paired with a
`FilterFunctions.uri(...)` filter that sets that attribute — `http()` alone has nowhere to proxy to.

Declarative (property-based) routes would use the `spring.cloud.gateway.server.webmvc.routes[*]`
prefix (note `server.webmvc`, not the older `spring.cloud.gateway.routes`), but this project only uses
the Java `RouterFunction` bean style — stay consistent with that rather than mixing in properties-based
routes.

### Rate limiting: two tiers, each with two Bucket4j bandwidths, on a Redis-primary/Caffeine-fallback store

The rate limit is `Bucket4jFilterFunctions.rateLimit(Consumer<RateLimitConfig>)`. It's applied
**twice**, once per `GatewayRoutesConfig` route — `browseOrdersRoute` (`GET /orders/**`, loose:
`gateway.rate-limit.browse.*`) and `checkoutOrdersRoute` (`POST /orders/**`, strict:
`gateway.rate-limit.checkout.*`) — because real e-commerce APIs don't apply one uniform limit
everywhere; placing an order is far more sensitive than browsing them. Both go through the shared
`tieredRoute(tier, predicate, ...)` helper.

Within each tier, two bandwidths apply at once (per-minute **and** per-day). `RateLimitConfig`'s
plain `setCapacity`/`setPeriod` only produce a single bandwidth, so instead
`config.setConfigurationBuilder(...)` is overridden to build a `BucketConfiguration` with **two**
`Bandwidth.classic(capacity, Refill.greedy(capacity, period))` entries via
`BucketConfiguration.builder().addLimit(...).addLimit(...)`. Bucket4j treats multiple bandwidths on
one bucket as AND: a request only succeeds if every bandwidth still has tokens. (`setCapacity`/
`setPeriod` are ignored once `setConfigurationBuilder` is set.)

**Keys are tier-scoped, not just client-scoped.** `RateLimitKeyResolver.forTier(tier)` resolves each
request's identity (an `X-User-Id` header when the caller sends one, else client IP — see its
Javadoc) and prefixes it with the tier name: `"browse:ip:1.2.3.4"` vs `"checkout:ip:1.2.3.4"`. Without
that prefix the two tiers would collide on one Bucket4j bucket per client, and whichever tier's
request created it first would silently fix its capacity/period for both — confirmed manually (see
below) that a client's checkout bucket exhausting doesn't touch their browse bucket.

Two non-obvious things this filter requires, both wired in `RateLimitBackendConfig`:

1. **Bucket4j itself isn't pulled in transitively.** The gateway's own dependency on it
   (`FilterAutoConfiguration$Bucket4jFilterConfiguration` is `@ConditionalOnClass(io.github.bucket4j.BucketConfiguration)`)
   is `optional`, so `com.bucket4j:bucket4j_jdk17-core` must be a direct dependency or the filter
   silently never activates.
2. **At request time the filter does `applicationContext.getBean(AsyncProxyManager.class)`** to get
   bucket storage — and `bucket4j_jdk17-core` ships no concrete implementation of that, only abstract
   scaffolding for distributed backends. `RateLimitBackendConfig.rateLimitProxyManager` provides one,
   **trying Redis first and falling back to Caffeine**:
   - Builds a Lettuce `RedisClient`/`StatefulRedisConnection` (`gateway.redis.host`/`port`, short
     `gateway.redis.connect-timeout-ms` so a missing Redis fails fast) and wraps it via
     `Bucket4jLettuce.casBasedBuilder(connection).build().asAsync()` (`bucket4j_jdk17-lettuce`) —
     this is a real distributed backend: multiple `api-gateway` instances pointed at the same Redis
     share buckets correctly.
   - If that connection attempt throws (Redis unreachable), it logs a warning and falls back to the
     original in-memory `CaffeineProxyManager` — so the project needs **no Docker/Redis install** to
     run, at the cost of per-instance (not cross-instance) limits while on the fallback.
   - `RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)` matters: it's what makes the
     connection's key type `String` (matching `RateLimitKeyResolver`'s output) — the simpler
     `Bucket4jLettuce.casBasedBuilder(RedisClient)` overload fixes the key type to `byte[]` instead
     and would break at runtime.

Verified manually (no Redis running in this environment, so on the Caffeine fallback — the startup
log says which backend is active): 6 rapid `POST /orders` from one client succeed 5 times then 429
(checkout's 5/minute), while `GET /orders` from that same client right after is unaffected (still
200s against its own 60/minute bucket) — proving both the dual-bandwidth limit and the tier
isolation. Response headers are tier-specific too (`X-RateLimit-browse-Remaining`,
`X-RateLimit-checkout-Remaining`). The Redis path itself (`gateway.redis.host` pointed at a real
Redis) is implemented and dependency/API-verified but **not** live-tested here — no Redis available
in this sandbox; verify by checking the startup log says "Rate limiting backed by Redis" instead of
the fallback warning.
