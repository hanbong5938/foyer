# Getting Started

Requirements: Java 17+, Spring Boot 4.0.x, Redis 7+. Artifacts are under `io.github.hanbong5938`.

Every service that **issues** tokens (runs the queue endpoints) or **checks** them (gateway filter, `protect`
paths) must share the same `foyer.secret` (≥ 32 characters). Generate one: `openssl rand -base64 48`.

## A. Spring Cloud Gateway in front of everything

```kotlin
dependencies {
    implementation("io.github.hanbong5938:foyer-gateway:0.1.0-alpha.1")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
}
```

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: booking
    uri: http://booking:8081
    predicates: [ Path=/booking/** ]
    filters: [ Foyer=concert-2026 ]      # room id, must exist under foyer.rooms

foyer:
  secret: ${FOYER_SECRET}
  rooms:
    concert-2026:
      rate: 200          # admissions per interval
      interval: 1s
      token-ttl: 5m
```

The gateway now:
- redirects browser navigations without a token to `/foyer/concert-2026/wait?redirect=<original>`;
- answers API calls without a token with `429` + `{"waitUrl": ...}`;
- serves the queue endpoints and the bundled wait page itself (the starter is included).

Start Redis (`docker run -p 6379:6379 redis`) and the gateway; open a protected URL.

## B. One Spring MVC or WebFlux application

```kotlin
dependencies {
    implementation("io.github.hanbong5938:foyer-spring-boot-starter:0.1.0-alpha.1")
}
```

```yaml
foyer:
  secret: ${FOYER_SECRET}
  rooms:
    concert-2026:
      rate: 200
      interval: 1s
      token-ttl: 5m
      protect: [ /booking/** ]    # HandlerInterceptor (MVC) or WebFilter (WebFlux)
```

## C. Behind a gateway, but also check in the service

Use A on the gateway and B (without the `protect` list if you only want the gateway to gate, or with it for
defence in depth) on the service, with the same secret. A token issued by the gateway verifies in the service.

## D. Custom front-end instead of the bundled wait page

Call the [HTTP API](HTTP-API) yourself: `POST /foyer/{room}/enter` with `Content-Type: application/json`, then
`GET /foyer/{room}/status` every `retryAfterMs`, until `state` is `ADMITTED`. The token cookie is set for you; for
non-browser clients send the `token` from the body as `X-Foyer-Token`.

## Examples in the repository

- `examples/booking-service` — MVC app (port 8081) protecting `/booking/**`
- `examples/gateway` — Gateway (port 8080) routing to it with `Foyer=concert-2026`
- `examples/loadtest` — docker-compose with 2 gateways behind nginx and a k6 script
