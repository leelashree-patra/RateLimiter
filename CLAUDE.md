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

### Rate limiting: a hand-rolled sliding-window counter, stored in Redis, Redis embedded by default

There's no rate-limiting *library* here — the limiter is `SlidingWindowCounter`, applied from a
plain `HandlerFilterFunction` lambda in `GatewayRoutesConfig.slidingWindowRoute`, used **twice**,
once per route — `browseOrdersRoute` (`GET /orders/**`, loose: `gateway.rate-limit.browse.*`) and
`checkoutOrdersRoute` (`POST /orders/**`, strict: `gateway.rate-limit.checkout.*`) — because real
e-commerce APIs don't apply one uniform limit everywhere; placing an order is far more sensitive
than browsing them.

**The algorithm**: time is split into fixed `windowSize` buckets, and a request is judged against a
weighted blend of the current and previous bucket (`previousCount * (1 - elapsedFraction) +
currentCount`) rather than a hard reset at each boundary (which would let a client burst up to 2x
its limit around a boundary) or an exact per-request log (which needs one stored entry per request
— doesn't scale for large volumes, which is why this is a *counter*, not a *log*). This needs
exactly two counters per key regardless of traffic.

**Where it's stored**: Redis, via one small Lua script run through Lettuce's `eval()` — no
rate-limiting library needed, since the whole check-and-increment runs as a single atomic operation
on the Redis server (Redis's single-threaded script execution gives atomicity for free; no
client-side CAS retry loop needed, unlike a local-storage version of the same algorithm). The script
(in `SlidingWindowCounter`) does `GET` current+previous, computes the estimate, and only if it's
under capacity does `INCR` the current bucket and `EXPIRE` it — mirroring Caffeine's
`expireAfterWrite` auto-eviction. The returned Lua table (`{allowed, remaining}`) arrives in Java as
`List<Object>` of `Long`s via `ScriptOutputType.MULTI`.

**Embedded Redis by default — no Docker, no Redis install needed to run this project.**
`RateLimitBackendConfig.embeddedRedisServer` starts `com.github.fppt:jedis-mock`, a pure-JVM
reimplementation of the Redis protocol (Netty + `luaj` for Lua/`EVAL` support) — **not** a real
`redis-server` binary. That distinction mattered here: `it.ozimov:embedded-redis` (which *does*
spawn a real binary) was checked first and ships only Linux/macOS executables, no Windows one, so
it's a dead end on a Windows dev box; `jedis-mock` has no OS dependency at all. Set
`gateway.redis.embedded=false` to instead connect to a real external Redis at
`gateway.redis.host`/`gateway.redis.port` — `jedis-mock` is a testing reimplementation, not
production-grade, so real deployments should use that path.

**Two granularities per tier, checked as AND, on two separate Redis-backed counters.**
`RateLimitBackendConfig` exposes `minuteWindowCounter`/`dayWindowCounter` beans (one
`SlidingWindowCounter` per granularity, `@Qualifier`-disambiguated since both are the same type),
shared by **both** tiers safely because `RateLimitKeyResolver.forTier(tier)` already prefixes every
key with the tier name (`"browse:ip:1.2.3.4"` vs `"checkout:ip:1.2.3.4"`) — without that prefix the
tiers would collide on one counter per client. `slidingWindowRoute` checks `minuteWindowCounter`
first; only if that allows the request does it check `dayWindowCounter`. If the day check then
rejects, `minuteWindowCounter.release(key)` gives the minute token back (a small, documented
approximation: a concurrent request on the same key could briefly see a slightly inflated count
between those two calls — acceptable since the algorithm is already an estimate).

Verified manually, against the embedded Redis (startup log confirms: `Embedded Redis (jedis-mock)
started on ...` / `Rate limiting backed by Redis at ... (embedded)`): 6 rapid `POST /orders` from one
client succeed 5 times then 429 (checkout's 5/minute), while `GET /orders` from that same client
right after is unaffected (still 200s against its own 60/minute counters) — proving both the
dual-granularity limit and the tier isolation. Response headers are tier-specific too
(`X-RateLimit-browse-Remaining`, `X-RateLimit-checkout-Remaining`). Also stress-tested with real
concurrency (`xargs -P`) through the running app: 90 parallel requests at a 60/window cap landed
exactly 60×200 + 30×429; 10 parallel at a 5/window cap landed exactly 5×201 + 5×429 — the Redis Lua
script's atomicity holds under contention, not just sequentially.
