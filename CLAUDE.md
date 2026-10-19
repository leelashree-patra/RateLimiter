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

### Rate limiting: two tiers, each with two Bucket4j bandwidths, on an in-memory Caffeine store

The rate limit is `Bucket4jFilterFunctions.rateLimit(Consumer<RateLimitConfig>)`. It's applied
**twice**, once per `GatewayRoutesConfig` route — `browseOrdersRoute` (`GET /orders/**`, loose:
`gateway.rate-limit.browse.*`) and `checkoutOrdersRoute` (`POST /orders/**`, strict:
`gateway.rate-limit.checkout.*`) — because real e-commerce APIs don't apply one uniform limit
everywhere; placing an order is far more sensitive than browsing them. Both go through the shared
`tieredRoute(tier, predicate, ...)` helper.

Within each tier, two bandwidths apply at once (per-minute **and** per-day).
`RateLimitConfig`'s plain `setCapacity`/`setPeriod` only produce a single bandwidth, so instead
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
below) that a client's checkout bucket exhausting doesn't touch their browse bucket. This also means
one shared Caffeine cache in `RateLimitBackendConfig` safely backs every tier.

Two non-obvious things this filter requires, both wired in `RateLimitBackendConfig`:

1. **Bucket4j itself isn't pulled in transitively.** The gateway's own dependency on it
   (`FilterAutoConfiguration$Bucket4jFilterConfiguration` is `@ConditionalOnClass(io.github.bucket4j.BucketConfiguration)`)
   is `optional`, so `com.bucket4j:bucket4j_jdk17-core` must be a direct dependency or the filter
   silently never activates.
2. **At request time the filter does `applicationContext.getBean(AsyncProxyManager.class)`** to get
   bucket storage — and `bucket4j_jdk17-core` ships no concrete implementation of that, only abstract
   scaffolding for distributed backends (JDBC, Redis, etc.). Something concrete has to be added and
   exposed as an `AsyncProxyManager` bean, or every request 500s with
   `NoSuchBeanDefinitionException: ... AsyncProxyManager`. This project uses an in-memory one:
   `com.bucket4j:bucket4j_jdk17-caffeine`'s `CaffeineProxyManager`, adapted with `.asAsync()`, wired
   as the `rateLimitProxyManager` bean. `com.github.ben-manes.caffeine:caffeine` is a plain
   dependency (version managed by the Spring Boot BOM) since bucket4j's Caffeine module marks it
   `provided`.

Because the proxy manager is in-JVM, buckets (and therefore rate limits) are **per gateway
instance** — multiple `api-gateway` replicas behind a load balancer would each enforce their own
counters. A distributed backend (e.g. Redis via `bucket4j_jdk17-lettuce`, already prototyped on the
`feature/redis-sliding-window-rate-limit` branch — not merged because this environment has no Redis
to verify it against) is a drop-in swap of just the `rateLimitProxyManager` bean in
`RateLimitBackendConfig`; `GatewayRoutesConfig` and `RateLimitKeyResolver` wouldn't need to change.

Verified manually: 6 rapid `POST /orders` from one client succeed 5 times then 429 (checkout's
5/minute), while `GET /orders` from that same client right after is unaffected (still 200s against
its own 60/minute bucket) — proving both the dual-bandwidth limit and the tier isolation. Response
headers are tier-specific too (`X-RateLimit-browse-Remaining`, `X-RateLimit-checkout-Remaining`).
