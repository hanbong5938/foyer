// k6: VISITORS visitors arrive within RAMP seconds, each enters, polls on the server's retryAfterMs
// until admitted, then fetches the protected page once with the issued token.
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE || "http://localhost:18080";
const VISITORS = Number(__ENV.VISITORS || 10000);
const RATE = Number(__ENV.RATE || 200);
const RAMP = Number(__ENV.RAMP || 10);
const ROOM = "concert-2026";

const admitted = new Counter("foyer_admitted");
const failOpen = new Counter("foyer_fail_open");
const unexpected = new Counter("foyer_unexpected");
const waitTime = new Trend("foyer_wait_ms", true);
const enterLatency = new Trend("foyer_enter_ms", true);
const statusLatency = new Trend("foyer_status_ms", true);
const positionAtEntry = new Trend("foyer_position_at_entry");

export const options = {
  scenarios: {
    visitors: {
      // All VISITORS arrive evenly within RAMP seconds, then wait their turn.
      executor: "constant-arrival-rate",
      rate: Math.ceil(VISITORS / RAMP), timeUnit: "1s", duration: RAMP + "s",
      preAllocatedVUs: VISITORS, maxVUs: VISITORS,
      gracefulStop: Math.ceil(VISITORS / RATE + 60) + "s",
    },
  },
  thresholds: {
    foyer_enter_ms: ["p(99)<100"],
    foyer_status_ms: ["p(99)<100"],
    foyer_unexpected: ["count==0"],
    http_req_failed: ["rate<0.001"],
  },
};

export default function () {
  // VUs are reused across iterations: drop the previous visitor's cookies so every iteration is a new visitor.
  http.cookieJar().clear(BASE);
  const t0 = Date.now();
  let res = http.post(`${BASE}/foyer/${ROOM}/enter`, "{}", { headers: { "Content-Type": "application/json" }, tags: { op: "enter" } });
  enterLatency.add(res.timings.duration);
  if (res.status !== 200) { unexpected.add(1); return; }
  let body = res.json();
  if (body.state === "WAITING") positionAtEntry.add(body.position);

  let polls = 0;
  while (body.state === "WAITING") {
    sleep(Math.max(0.2, (body.retryAfterMs || 1000) / 1000));
    res = http.get(`${BASE}/foyer/${ROOM}/status`, { tags: { op: "status" } });
    statusLatency.add(res.timings.duration);
    if (res.status !== 200) { unexpected.add(1); return; }
    body = res.json();
    if (++polls > 2000) { unexpected.add(1); return; }
  }
  if (body.state !== "ADMITTED") { unexpected.add(1); return; }
  admitted.add(1);
  if (body.failOpen) failOpen.add(1);
  waitTime.add(Date.now() - t0);

  const page = http.get(`${BASE}/booking/seats`, { tags: { op: "protected" } });
  check(page, { "protected 200 with token": (r) => r.status === 200 }) || unexpected.add(1);
}
