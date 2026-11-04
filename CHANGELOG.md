# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com/).

## [0.1.0-alpha.1] — 2026-11-04

### Added
- `foyer-core`: `WaitingRoom` port, `AdmissionPolicy` token bucket, `HmacTokenCodec` (HS256 JWT), `PollBackoff`.
- `foyer-redis`: `LettuceWaitingRoom` with atomic Lua admission; `LazyLettuceConnection` for standalone, Sentinel
  and Cluster with lazy connect and NOSCRIPT recovery.
- `foyer-spring-boot-starter`: auto-configuration, `foyer.*` properties, MVC + WebFlux queue endpoints, bundled
  wait page, `protect` paths, Micrometer metrics, fail-open/fail-closed policy.
- `foyer-gateway`: `Foyer=<room>` `GatewayFilterFactory` for Spring Cloud Gateway.
- Examples: `booking-service`, `gateway`, `loadtest` (docker-compose + k6).

[0.1.0-alpha.1]: https://github.com/hanbong5938/foyer/releases/tag/v0.1.0-alpha.1
