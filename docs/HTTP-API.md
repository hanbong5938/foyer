# HTTP API

Registered automatically in MVC and WebFlux apps (and therefore in the gateway). `{base}` = `foyer.base-path`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `{base}/{room}/enter` | Join the line, or re-check if already in it / admitted. Requires `Content-Type: application/json` (body may be empty or `{}`); other types get `415`. Sets the visitor cookie if absent. |
| `GET` | `{base}/{room}/status` | Poll. Without a visitor cookie returns `NOT_IN_QUEUE` (call `enter`). |
| `DELETE` | `{base}/{room}` | Leave the line. `204`. |
| `GET` | `{base}/{room}/wait?redirect=/path` | Bundled wait page. `redirect` must be a same-origin path, else `/`. |

## Response body (`enter`, `status`)

```json
{
  "state": "WAITING",          // WAITING | ADMITTED | NOT_IN_QUEUE
  "position": 3,               // 0-based; -1 unless WAITING
  "queueSize": 42,
  "retryAfterMs": 2000,        // WAITING only
  "admittedUntil": null,       // ADMITTED only, epoch millis
  "token": null,               // ADMITTED only, the JWT (also set as cookie)
  "failOpen": false            // true when admitted because Redis was unreachable
}
```

## Cookies set by the queue endpoints

| Cookie | When | Attributes |
|---|---|---|
| `foyer-visitor` | first `enter` | HttpOnly, 1 day |
| `foyer-token-<room>` | on `ADMITTED` | HttpOnly, max-age = remaining `token-ttl` |

## Presenting a token on protected paths

Checked in this order: header `X-Foyer-Token`, then cookie `foyer-token-<room>`.

Without a valid token for **that room**:

| Client | Response |
|---|---|
| Browser navigation (`Sec-Fetch-Mode: navigate`, or `Accept: text/html` when absent) | `302 Location: {base}/{room}/wait?redirect=<path+query>` |
| Anything else | `429`, `Retry-After: 1`, `{"error":"waiting_room","room":"…","waitUrl":"…"}` |

Both carry `Cache-Control: no-store`. A token for another room, an expired token, or one signed with a different
secret is rejected.

## Errors

| Status | Body | Cause |
|---|---|---|
| `404` | `{"error":"unknown_room"}` | Room not under `foyer.rooms` |
| `415` | `{"error":"unsupported_media_type"}` | `enter` without `Content-Type: application/json` |
| `503` | `{"error":"queue_unavailable"}` + `Retry-After` | Redis unreachable and `fail-open=false` |
