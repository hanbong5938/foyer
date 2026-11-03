# Configuration

All properties live under `foyer`. Durations use Spring's format (`1s`, `5m`, `500ms`).

## Global

| Property | Default | Notes |
|---|---|---|
| `foyer.secret` | — **required** | HMAC-SHA256 secret, ≥ 32 characters. Same value on every service that issues or checks tokens. |
| `foyer.fail-open` | `true` | Redis unreachable: `true` admits everyone (token flagged `failOpen`), `false` answers `503` with `Retry-After`. |
| `foyer.base-path` | `/foyer` | Prefix of the queue endpoints and wait page. |
| `foyer.poll.min` | `1s` | Lower bound of the `retryAfterMs` suggested to waiting clients. |
| `foyer.poll.max` | `10s` | Upper bound. Every room's `queue-ttl` must exceed this. |

## Cookies

| Property | Default | Notes |
|---|---|---|
| `foyer.cookie.token-name` | `foyer-token` | Prefix; the per-room cookie is `<token-name>-<room>` (one token per room). |
| `foyer.cookie.visitor-name` | `foyer-visitor` | Anonymous visitor id holding the place in line. Treat as a bearer secret; it is `HttpOnly`. |
| `foyer.cookie.secure` | `false` | Set `true` behind HTTPS. |
| `foyer.cookie.same-site` | `Lax` | |
| `foyer.cookie.path` | `/` | |
| `foyer.cookie.domain` | — | Set to share the token across subdomains (gateway on `www.`, API on `api.`). |

## Redis

| Property | Default | Notes |
|---|---|---|
| `foyer.redis.uri` | `redis://localhost:6379` | Standalone or Sentinel (`redis-sentinel://…`) URI. |
| `foyer.redis.cluster-nodes` | `[]` | Seed URIs; when non-empty a `RedisClusterClient` is used and `uri` is ignored. |
| `foyer.redis.key-prefix` | `foyer` | Keys are `<prefix>:{<room>}:queue|seen|active|meta`. |

The connection is opened lazily on first use, so the app starts while Redis is down. To bring your own
connection define a `StatefulRedisConnection<String,String>` bean; to replace Redis entirely define a
`dev.foyer.core.WaitingRoom` bean (then no Redis client is created).

## Rooms — `foyer.rooms.<id>.*`

Room ids: `[A-Za-z0-9_-]{1,64}`.

| Property | Default | Notes |
|---|---|---|
| `rate` | `10` | Admissions gained per `interval`. |
| `interval` | `1s` | Refill period. |
| `burst` | = `rate` | Bucket capacity: how many enter at once after an idle period. |
| `max-active` | `0` | Cap on visitors whose admission has not expired. `0` = unlimited. |
| `token-ttl` | `5m` | Lifetime of an admission (token and `active` entry). |
| `queue-ttl` | `30s` | Waiters silent longer than this lose their place. Must be `> poll.max`; `0` disables. |
| `protect` | `[]` | Ant-style paths in this app requiring a token for this room. |

### Choosing values

- `rate`/`interval`: what the protected backend sustains. 200/1s and 12000/1m behave the same on average; the
  shorter interval gives smoother admission.
- `token-ttl`: how long a user may stay on the protected pages. Longer TTL with `max-active` set = fewer concurrent
  users admitted.
- `queue-ttl`: the bundled wait page polls at most every `poll.max`; 30s default leaves three missed polls before
  eviction. Abandoned tabs hold their place for this long.

## Env vars and Docker

Room ids contain a dash, which Spring's relaxed binding cannot express as an environment variable. Use
`SPRING_APPLICATION_JSON='{"foyer":{"rooms":{"concert-2026":{"rate":200}}}}'` or a mounted YAML.
