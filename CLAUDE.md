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

- **`api-gateway`** — the gateway. Routes `/orders/**` to `order-service` and rate-limits each
  request by client IP before proxying. `ApiGatewayApplication` (boot entrypoint) sits in
  `org.example.ratelimiter.gateway`; `GatewayRoutesConfig` (the route + rate-limit bean, below) is in
  its `.config` sub-package.
- **`order-service`** — the backend being proxied to (`org.example.orderservice`), split into
  `entity` (`Order` record), `store` (`OrderStore`, in-memory `ConcurrentHashMap`, no persistence
  dependency), and `controller` (`OrderController`: `GET /orders`, `GET /orders/{id}`, `POST /orders`).

### `api-gateway` is the WebMVC gateway variant, not the reactive one

It depends on `spring-cloud-starter-gateway-server-webmvc`, the servlet/blocking flavor of Spring
Cloud Gateway. Routing and filtering code lives under `org.springframework.cloud.gateway.server.mvc.*`,
built on Spring MVC's functional endpoints — **not** `RouteLocatorBuilder`, `GatewayFilter`,
`GlobalFilter`, `KeyResolver`, or anything returning `Mono`/`Flux`. Most Spring Cloud Gateway material
online targets the reactive server and will not compile here.

`GatewayRoutesConfig.orderServiceRoute` shows the pattern used throughout this module:
`GatewayRouterFunctions.route(name).route(predicate, HandlerFunctions.http())` builds the
`RouterFunction<ServerResponse>` bean; `HandlerFunctions.http()` is a generic proxying handler that
reads its target URI from a request attribute, so it's always paired with a
`FilterFunctions.uri(...)` filter that sets that attribute — `http()` alone has nowhere to proxy to.

Declarative (property-based) routes would use the `spring.cloud.gateway.server.webmvc.routes[*]`
prefix (note `server.webmvc`, not the older `spring.cloud.gateway.routes`), but this project only uses
the Java `RouterFunction` bean style — stay consistent with that rather than mixing in properties-based
routes.

### Rate limiting: two Bucket4j bandwidths in one bucket, and the proxy-manager bean it needs

The rate limit is `Bucket4jFilterFunctions.rateLimit(Consumer<RateLimitConfig>)`, configured in
`GatewayRoutesConfig` with a key resolver that reads `ServerRequest.remoteAddress()` (the caller's
IP). The requirement is two simultaneous limits per client — 5/minute **and** 20/day
(`gateway.rate-limit.per-minute.capacity` / `gateway.rate-limit.per-day.capacity`) — not just one.
`RateLimitConfig`'s plain `setCapacity`/`setPeriod` only produce a single bandwidth, so instead
`config.setConfigurationBuilder(...)` is overridden to build a `BucketConfiguration` with **two**
`Bandwidth.classic(capacity, Refill.greedy(capacity, period))` entries via
`BucketConfiguration.builder().addLimit(...).addLimit(...)`. Bucket4j treats multiple bandwidths on
one bucket as AND: a request only succeeds if every bandwidth still has tokens, so hitting either the
per-minute or the per-day cap alone is enough to get a 429 — adding a third tier is just another
`addLimit(...)` call. (`setCapacity`/`setPeriod` are ignored once `setConfigurationBuilder` is set;
nothing in the gateway's defaults requires them to be populated.)

Two non-obvious things this filter requires, both already wired in `api-gateway`'s `pom.xml` /
`GatewayRoutesConfig` — easy to miss when touching either:

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
   as the `rateLimitProxyManager` bean in `GatewayRoutesConfig`. `com.github.ben-manes.caffeine:caffeine`
   is a plain dependency (version managed by the Spring Boot BOM) since bucket4j's Caffeine module
   marks it `provided`.

Because the proxy manager is in-JVM, buckets (and therefore rate limits) are **per gateway instance**.
Running multiple `api-gateway` replicas behind a load balancer would need a distributed
`AsyncProxyManager` backend (e.g. Redis via `bucket4j-redis`) instead of the Caffeine one — swap the
`rateLimitProxyManager` bean, nothing else changes.

Verified manually: with the default `per-minute.capacity=5`, the 6th rapid request from the same IP
within a minute gets HTTP 429; requests succeed again later as Bucket4j's greedy refill adds tokens
back proportionally to elapsed time (not just at the end of the window).
