# Release Notes

## 0.1.0-alpha.1 — first public release

**Modules** (`io.github.hanbong5938`): `foyer-core`, `foyer-redis`, `foyer-spring-boot-starter`, `foyer-gateway`.

**Features**
- Lazy admission in one atomic Redis Lua script: token bucket (`rate`/`interval`/`burst`), `max-active` cap,
  idle eviction (`queue-ttl`), re-reporting of existing admissions. No scheduler, no leader election.
- Stateless HS256 JWT admission tokens; verification needs only the shared secret.
- Spring Boot starter: auto-configuration, `foyer.*` properties, queue endpoints and bundled wait page for both
  MVC and WebFlux, `protect` paths (HandlerInterceptor / WebFilter), Micrometer metrics.
- Spring Cloud Gateway filter: `filters: [ Foyer=<room> ]`.
- Fail-open (default) or fail-closed when Redis is unreachable; lazy connection so apps start without Redis.
- Redis standalone, Sentinel (URI) and Cluster (`cluster-nodes`).
- Server-driven poll interval (`retryAfterMs`).

**Verification**
- 151 tests including Testcontainers: 500 concurrent visitors admitted in exact enrollment order with shuffled
  polling; cluster routing; fail-open/closed; MVC and WebFlux end-to-end.
- Browser flow (redirect → wait page → poll → admit → redirect back) in headless Chromium/Brave.
- Load: 10k visitors, 2 gateway instances, 200 admits/s — 0 failures, `enter` p99 2.5 ms, cross-instance admit
  counters exact.

**Known limitations**
- Spring Boot 4.0.x only (Boot 3.x has a different auto-configuration layout).
- No token revocation before expiry.
- Alpha: property names and the `WaitingRoom` port may still change before 0.1.0.
