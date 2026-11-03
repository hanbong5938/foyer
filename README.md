# Foyer

A virtual waiting room for Spring Boot. When a protected path gets more traffic than your backend can take, visitors
wait in a fair FIFO line and are let in at a fixed rate. Each admitted visitor gets a signed admission token.

- **Lazy admission.** Nothing runs in the background: no scheduler, no leader election, no worker. A visitor is
  admitted while their own `enter`/`status` request runs, inside one atomic Redis Lua script.
- **Stateless gate.** Protected requests are checked against a signed HS256 JWT (`iss=foyer`, `sub=<visitor>`,
  `room`, `iat`, `exp`). The check never calls Redis, so gateways and services stay fast and keep working when the
  store is slow. Any JWT library can verify the token with the shared secret. `iat`/`exp` are NumericDates with the
  admission's fractional seconds preserved (`1767225600.6`), so a token expires exactly when the store's admission
  does; a verifier that truncates NumericDates to whole seconds rejects it up to a second early, never late.
- **Fail-open by default.** If Redis is unreachable, `enter`/`status` admit the visitor instead of failing
  (`foyer.fail-open=true`). Set it to `false` to answer `503` instead. The Redis connection is opened lazily, so the
  application also starts while Redis is down.
- **Bundled wait page.** Browsers without a token are redirected to an HTML page that joins the line, shows the
  position, polls with server-suggested backoff and goes back to the original URL once admitted.

## Modules

| Artifact                                 | Use it for                                                                      |
|------------------------------------------|---------------------------------------------------------------------------------|
| `dev.foyer:foyer-spring-boot-starter`    | Spring MVC or WebFlux apps: queue endpoints, wait page, `protect` paths, metrics |
| `dev.foyer:foyer-gateway`                | Spring Cloud Gateway (WebFlux): the `Foyer` route filter, plus everything above  |
| `dev.foyer:foyer-core`, `foyer-redis`    | Framework-free port (`WaitingRoom`), token codec, Lettuce/Lua implementation     |

## Quickstart

Every service that issues or checks tokens must share the same `foyer.secret` (at least 32 characters).

### a) Spring Cloud Gateway

```kotlin
dependencies {
    implementation("dev.foyer:foyer-gateway:0.1.0-SNAPSHOT")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
}
```

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: booking
    uri: http://booking:8081
    predicates: [ Path=/booking/** ]
    filters: [ Foyer=concert-2026 ]   # room id from foyer.rooms

foyer:
  secret: ${FOYER_SECRET}
  rooms:
    concert-2026:
      rate: 2          # admissions per interval
      interval: 1s
      token-ttl: 5m
```

The gateway also serves the queue endpoints and the wait page under `/foyer`. A route that names a room missing
from `foyer.rooms` fails with `IllegalArgumentException` when the route is built.
When `StripPrefix` or `RewritePath` runs before `Foyer`, wait-page return URLs retain the earliest original
client path and raw query. Admitted requests still forward using the rewritten backend path.

### b) Spring MVC (or WebFlux) application

```kotlin
dependencies {
    implementation("dev.foyer:foyer-spring-boot-starter:0.1.0-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
```

```yaml
foyer:
  secret: ${FOYER_SECRET}
  rooms:
    concert-2026:
      rate: 2
      interval: 1s
      token-ttl: 5m
      protect: [ /booking/** ]   # Ant-style paths guarded by a HandlerInterceptor (MVC) or WebFilter (WebFlux)
```

### Gate behaviour on protected paths

The token is read from the `X-Foyer-Token` header, else from the cookie `<cookie.token-name>-<room>`
(`foyer-token-concert-2026`). Without a valid token for that room:

- browser navigation (`Sec-Fetch-Mode: navigate`, or `Accept` containing `text/html` when that header is absent) →
  `302` to `{base-path}/{room}/wait?redirect=<original path and query>`;
- anything else → `429`, `Retry-After: 1`, body
  `{"error":"waiting_room","room":"concert-2026","waitUrl":"/foyer/concert-2026/wait?redirect=..."}`.

Both answers carry `Cache-Control: no-store`. Wait URLs include the application's context path (`server.servlet.context-path`
/ `spring.webflux.base-path`), so an app mounted at `/app` redirects to `/app/foyer/...`. When several rooms' `protect`
patterns match one path, the request needs a valid token for every one of them; the first room (in configuration order)
lacking one answers. Requests to the queue endpoints under `foyer.base-path` are never gated, even by a `/**` protect
pattern, and CORS preflights (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) are left to Spring's CORS
configuration; a plain `OPTIONS` is gated like any other request. On WebFlux, a denied request carrying an `Origin`
header is first matched against the application's `DispatcherHandler` mappings so the `302`/`429` carries the CORS
headers the matched handler would have applied (global `addCorsMappings` and `@CrossOrigin`); a disallowed origin gets
Spring's `403`. The handler itself never runs. Applications that replace the `webHandler` bean keep their own CORS handling.
In a Spring Cloud Gateway application, Gateway routes are not consulted by this lookup (their predicates may read the
request body): gate routes with the `Foyer` gateway filter, which applies the Gateway's own CORS configuration.
The admission token cookie's `Max-Age` is the token's remaining lifetime rounded up to whole seconds; the token's own
`exp` is what the gate checks.

## Endpoints

Registered automatically for MVC and WebFlux applications. `{base}` is `foyer.base-path` (default `/foyer`).

| Method   | Path                              | Description                                                                                       |
|----------|-----------------------------------|---------------------------------------------------------------------------------------------------|
| `POST`   | `{base}/{room}/enter`             | Join the line (or re-check). Sets the visitor cookie; sets the token cookie when admitted.        |
| `GET`    | `{base}/{room}/status`            | Poll. Sets the token cookie once admitted. `NOT_IN_QUEUE` without a (valid) visitor cookie.       |
| `DELETE` | `{base}/{room}`                   | Leave the line. `204`.                                                                            |
| `GET`    | `{base}/{room}/wait?redirect=/p`  | Bundled HTML wait page; only same-origin paths are accepted as `redirect`.                        |

The visitor cookie is a lower-case canonical UUID issued by `enter`; any other value is treated as absent (`enter` issues
a fresh one, `status` answers `NOT_IN_QUEUE`, `DELETE` answers `204`) and never reaches the store.

`POST /enter` requires `Content-Type: application/json` (optional parameters such as `charset=UTF-8` are accepted).
An empty payload is valid; no JSON body is consumed. Missing, malformed, wildcard, form, text and `+json` media types
receive `415 {"error":"unsupported_media_type","room":"…"}` before room lookup or identity mutation, with no cookies.
This prevents cross-site HTML forms from replacing the shared visitor identity; it is not Origin validation.
Cross-origin JSON clients remain subject to the application's CORS policy. Granting an untrusted origin CORS access
also grants it access to this endpoint. Entry replies and status replies use `Cache-Control: no-store`.
On MVC, invalid entry media types are rejected before functional request construction (which otherwise throws on
wildcards); those early 415 replies do not include MVC CORS response headers. Valid JSON and preflights use normal CORS processing.

`enter` and `status` return:

```json
{"state":"WAITING","position":3,"queueSize":42,"retryAfterMs":2000,"admittedUntil":null,"token":null,"failOpen":false}
```

`state` is `WAITING`, `ADMITTED` or `NOT_IN_QUEUE`; `position` is 0-based (`-1` unless waiting); `admittedUntil` is
epoch millis; `token` is the admission token (also set as an HttpOnly cookie); `failOpen` is `true` when admitted
because Redis was unreachable. Unknown rooms answer `404 {"error":"unknown_room"}`; with `fail-open=false` an
unreachable store answers `503 {"error":"queue_unavailable"}` with `Retry-After`.
In fail-open mode, a store failure during leave is ignored rather than granting admission. Its warning says
`ignoring leave`; admission failures say `admitting visitors ... without queueing`. Both share a 10-second warning throttle.

## Configuration

| Property                          | Default                  | Description                                                                       |
|-----------------------------------|--------------------------|-----------------------------------------------------------------------------------|
| `foyer.secret`                    | — (required)             | HMAC secret, at least 32 characters. Shared by every service that checks tokens.  |
| `foyer.fail-open`                 | `true`                   | Store unreachable: admit everyone (`true`) or answer `503` (`false`).              |
| `foyer.base-path`                 | `/foyer`                 | Prefix of the queue endpoints and the wait page: `/segment(/segment)*` of `[A-Za-z0-9_-]`, no trailing slash. |
| `foyer.cookie.token-name`         | `foyer-token`            | Token cookie prefix; the per-room cookie is `<token-name>-<room>`.                |
| `foyer.cookie.visitor-name`       | `foyer-visitor`          | Cookie holding the anonymous visitor id.                                          |
| `foyer.cookie.secure`             | `false`                  | `Secure` attribute of both cookies.                                               |
| `foyer.cookie.same-site`          | `Lax`                    | `SameSite` attribute.                                                             |
| `foyer.cookie.path`               | `/`                      | Cookie path.                                                                      |
| `foyer.cookie.domain`             | —                        | Cookie domain, e.g. to share the token across subdomains.                         |
| `foyer.poll.min` / `poll.max`     | `1s` / `10s`             | Bounds of the poll interval suggested to waiting clients (`retryAfterMs`).        |
| `foyer.redis.uri`                 | `redis://localhost:6379` | Used only when the app defines no `StatefulRedisConnection<String,String>` bean.  |
| `foyer.redis.key-prefix`          | `foyer`                  | Prefix of every Redis key Foyer writes.                                           |
| `foyer.rooms.<id>.rate`           | `10`                     | Admissions per `interval`.                                                        |
| `foyer.rooms.<id>.interval`       | `1s`                     | Refill interval of the token bucket. Durations are whole milliseconds, at least `1ms`. |
| `foyer.rooms.<id>.burst`          | = `rate`                 | Bucket capacity: how many may enter at once after an idle period.                 |
| `foyer.rooms.<id>.max-active`     | `0` (unlimited)          | Cap on concurrently admitted visitors (admissions whose `token-ttl` has not run out). |
| `foyer.rooms.<id>.token-ttl`      | `5m`                     | Lifetime of an admission token (whole milliseconds, at least `1ms`).              |
| `foyer.rooms.<id>.queue-ttl`      | `30s`                    | Waiters silent longer than this lose their place. Must exceed `foyer.poll.max`; `0` disables eviction. Whole milliseconds. |
| `foyer.rooms.<id>.protect`        | `[]`                     | Ant-style paths in this app that require a token for the room (MVC/WebFlux).      |

Room ids must match `[A-Za-z0-9_-]{1,64}`. Invalid ids and `queue-ttl <= poll.max` fail at startup.

The starter's non-web services (`FoyerService`, `AdmissionGate`, `RoomRegistry`) work without Spring Web.
Foyer injects its clock by the bean name `foyerClock`; define that bean to override it. Its own clock is not an
unqualified injection candidate and does not interfere with application `Clock` beans, including primary ones.

## How it works

Each room is four Redis keys: the line (sorted by enrollment sequence), last-seen times, admitted visitors (by
expiry) and a token bucket (`tokens`, `refillAt`). Every `enter`/`status` runs one Lua script that, atomically:

1. drops expired admissions, so `max-active` frees up;
2. evicts waiters not seen for `queue-ttl` (bounded per call), so abandoned tabs stop holding places;
3. re-reports an existing admission, so a lost response never loses it;
4. finds the visitor's position, enrolling them on `enter` only;
5. refills the bucket lazily from elapsed time (`rate` per `interval`, capped at `burst`; a fresh room starts full);
6. admits the visitor if `position < budget`, where budget is the bucket's tokens, further capped by
   `max-active - active`. Admission consumes one token.

Visitors ahead keep their place even if a later visitor polls first: the budget counts everyone ahead, so nobody is
overtaken; the front of the line simply has to poll to collect. Because admission happens on poll, `queue-ttl` must
exceed `poll.max`, otherwise well-behaved clients would be evicted between polls.

To use another store, define your own `dev.foyer.core.WaitingRoom` bean; the starter then creates no Redis client.
Implementations of `WaitingRoom.stats(room, policy)` receive the actual room policy and must count live waiting
positions, including all waiters when `queueTtl` is zero.

## Metrics

With Micrometer on the classpath (and no custom `FoyerEventListener` bean):

| Meter                 | Type    | Tags                 |
|-----------------------|---------|----------------------|
| `foyer.enter`         | counter | `room`               |
| `foyer.poll`          | counter | `room`, `state`      |
| `foyer.admit`         | counter | `room`, `fail_open`  |
| `foyer.queue.waiting` | gauge   | `room` (sampled from the store asynchronously, at most once per second; NaN until the first sample or when unreachable) |
| `foyer.queue.active`  | gauge   | `room` (same sample as `foyer.queue.waiting`)                                                                         |

Waiting counts exclude positions whose last-seen time is at or before `now - queueTtl`, even when no visitor
has polled to trigger lazy eviction. Redis samples count the last-seen sorted set without scanning or removing
queue members; disabled TTL counts the entire queue. Active counts exclude expired admissions.

## Running the examples

`examples/booking-service` is an MVC app on port 8081 protecting `/booking/**`; `examples/gateway` is a Spring
Cloud Gateway on port 8080 routing `/booking/**` to it through `Foyer=concert-2026`. Both use room `concert-2026`
at 2 admissions/second and share the same secret, so the token cookie the gateway issues also passes the
booking-service's own check.

```sh
docker run -p 6379:6379 redis
./gradlew :examples:booking-service:bootRun
./gradlew :examples:gateway:bootRun
```

Open <http://localhost:8080/booking/seats>. The first visitors go straight through; open more private windows (or
script `curl -X POST -H 'Content-Type: application/json' localhost:8080/foyer/concert-2026/enter`) to see the line form.

## Supported versions

- Spring Boot 4.0.x
- Spring Cloud 2025.1.x (Gateway Server WebFlux 5.0.x)
- Java 17+
- Redis 7+

## License

Apache-2.0
