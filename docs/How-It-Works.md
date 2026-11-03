# How It Works

## Two separate concerns

1. **Queueing** — who may enter next. Lives in Redis, driven by one Lua script.
2. **Gating** — may this request pass. Stateless: an HS256 JWT (`iss=foyer`, `sub=<visitor>`, `room`, `iat`,
   `exp`) verified with the shared secret. Gateways and services never call Redis on the hot path.

Any JWT library can verify the token, so non-JVM services can sit behind the same gate.

## No scheduler, no leader

Admission happens **lazily, inside the visitor's own poll**. There is no background worker deciding who enters,
hence nothing to elect or lock across instances. Any number of gateways/apps share one Redis and stay consistent
because the script is atomic.

## Redis keys (per room, hash-tagged so cluster routes them to one slot)

| Key | Type | Content |
|---|---|---|
| `foyer:{room}:queue` | ZSET | visitor → enrollment sequence (the line order) |
| `foyer:{room}:seen` | ZSET | visitor → last poll time (idle eviction) |
| `foyer:{room}:active` | ZSET | visitor → admission expiry |
| `foyer:{room}:meta` | HASH | `seq` counter, token bucket `tokens` and `refillAt` |

## The script, step by step (`admit.lua`)

1. `ZREMRANGEBYSCORE active -inf now` — expired admissions free `max-active` capacity.
2. Evict waiters whose `seen` is older than `queue-ttl` (at most 100 per call, so cost is bounded).
3. If the visitor is in `active`: return `ADMITTED` with the same expiry. A lost HTTP response never loses an
   admission.
4. Find the visitor's position (`ZRANK queue`). On `enter`, enroll if absent (`HINCRBY seq`, `ZADD`); on `status`,
   return `NOT_IN_QUEUE` instead. Record `seen`.
5. Refill the bucket from elapsed time: `tokens = min(burst, tokens + floor((now - refillAt)/interval) * rate)`.
   A fresh room starts full.
6. `budget = tokens`, further capped by `max-active - |active|`. If `position < budget`: consume a token, move
   the visitor from `queue`/`seen` to `active` with expiry `now + token-ttl`, return `ADMITTED`. Otherwise return
   `WAITING` with the position.

### Why `position < budget` keeps order

The budget counts everyone ahead. A visitor at position 3 with 10 tokens is admitted and the budget drops to 9,
so the three ahead still fit when they poll. Nobody behind can take a slot that someone ahead has not yet
collected; the front of the line merely has to poll to collect. Verified by a test with 500 concurrent visitors and
shuffled poll order: every round admits exactly the next `rate` visitors in enrollment order.

### Why `queue-ttl` must exceed `poll.max`

Because collection happens on poll, a client that polls every `poll.max` must not be evicted in between.
Startup validation rejects `queue-ttl <= poll.max`.

## Server-driven polling

`retryAfterMs = clamp(estimatedTimeToFront / 2, poll.min, poll.max)` where the estimate is
`(position + 1) / rate * interval`. Far-back visitors poll rarely, so poll load is proportional to admission rate,
not queue length. In the 10k-visitor load test this was ~1.3k requests/s total.
