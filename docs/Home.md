# Foyer

Virtual waiting room for Spring Boot. Visitors queue in a fair FIFO line in Redis, are admitted at a fixed rate,
and carry a signed JWT that any gateway or service verifies without touching Redis.

| Page | What it covers |
|---|---|
| [Getting Started](Getting-Started) | Dependencies and the two 30-second setups (Gateway filter, MVC/WebFlux `protect`) |
| [Configuration](Configuration) | Every `foyer.*` property, defaults and constraints |
| [How It Works](How-It-Works) | Redis keys, the Lua script, token bucket, lazy admission, eviction |
| [HTTP API](HTTP-API) | Queue endpoints, wire format, cookies, status codes |
| [Operations](Operations) | Fail-open vs fail-closed, metrics, Redis Cluster, multi-instance, load numbers |
| [Release Notes](Release-Notes) | Versions and changes |

Source: https://github.com/hanbong5938/foyer · License: Apache-2.0
