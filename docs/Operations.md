# Operations

## Redis outage

| `foyer.fail-open` | `enter` / `status` | Protected paths |
|---|---|---|
| `true` (default) | `200 ADMITTED`, `failOpen: true`, real token issued | unaffected (stateless check) |
| `false` | `503` + `Retry-After: <poll.min>` | unaffected |

Fail-open logs one warning per 10 s and increments `foyer.admit{fail_open="true"}`; alert on that counter.
The Redis connection is lazy: apps start while Redis is down and connect on first use; once connected, Lettuce
auto-reconnects.

## Multiple instances

Nothing is instance-local. Run as many gateways/apps as you like against one Redis; the script is atomic.
Verified with 2 gateways behind nginx: per-instance `foyer_admit_total` deltas summed exactly to the number of
admitted visitors (10,001), at 196.8/s against a 200/s limit.

## Redis Cluster / Sentinel

- Cluster: `foyer.redis.cluster-nodes: [redis://n1:7000, redis://n2:7000]`. Keys are hash-tagged `{room}`, so each
  script call is single-slot.
- Sentinel: `foyer.redis.uri: redis-sentinel://host1:26379,host2:26379/0#mymaster`.

## Metrics (Micrometer)

| Meter | Type | Tags |
|---|---|---|
| `foyer.enter` | counter | `room` |
| `foyer.poll` | counter | `room`, `state` |
| `foyer.admit` | counter | `room`, `fail_open` |
| `foyer.queue.waiting` | gauge | `room` — sampled from Redis ≤ 1/s; NaN until sampled or when unreachable |
| `foyer.queue.active` | gauge | `room` |

Useful alerts: `rate(foyer_admit_total{fail_open="true"}[1m]) > 0`; `foyer_queue_waiting` above the number you
can drain within your SLA (`waiting / rate` seconds).

## Capacity numbers (10,000 visitors in 10 s, 2 gateways, 200 admits/s, laptop Docker)

| Measure | Value |
|---|---|
| Failed requests | 0 of 71,093 |
| `enter` latency | med 0.36 ms · p99 2.5 ms |
| `status` latency | med 0.37 ms · p99 0.9 ms |
| Redis commands > 1 ms | 0 of 732k |
| Total request rate | ~1.3k/s for 10k waiting visitors |

Cold JVMs show `enter` p95 ≈ 200 ms during the first burst (JIT); warm the gateways or accept it.

## Redis memory

Per waiting visitor: one member in `queue` and one in `seen` (~150 B together with a UUID id). 1M waiting ≈ 150 MB.
Per admitted visitor: one member in `active` until `token-ttl`. Nothing else accumulates; `meta` is 3 fields.

## Security notes

- The visitor cookie is a bearer secret for the place in line (random UUID v4). It is `HttpOnly`; set
  `foyer.cookie.secure=true` on HTTPS.
- Tokens cannot be revoked before `exp`; keep `token-ttl` short. Rotating `foyer.secret` invalidates all tokens.
- The wait page accepts only same-origin `redirect` paths.
- `enter` requires `Content-Type: application/json`, which blocks cross-site HTML forms from overwriting a
  visitor's identity.
