-- Atomic enter/poll for one visitor in one room.
--
-- KEYS[1] queue   ZSET visitor -> enrollment sequence (defines the order of the line)
-- KEYS[2] seen    ZSET visitor -> last poll time ms (idle eviction)
-- KEYS[3] active  ZSET visitor -> admission expiry ms
-- KEYS[4] meta    HASH seq, tokens, refillAt
--
-- ARGV[1] visitor
-- ARGV[2] now ms
-- ARGV[3] enroll: "1" joins the line when absent, "0" reports NOT_IN_QUEUE instead
-- ARGV[4] rate            admissions per interval
-- ARGV[5] interval ms
-- ARGV[6] burst           bucket capacity
-- ARGV[7] maxActive       0 = unlimited
-- ARGV[8] tokenTtl ms
-- ARGV[9] queueTtl ms     0 = never evict idle waiters
--
-- Reply: { state, position, queueSize, admittedUntilMs }
--   state is "ADMITTED" | "WAITING" | "NOT_IN_QUEUE"; position is -1 unless WAITING.

local queue, seen, active, meta = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local visitor   = ARGV[1]
local now       = tonumber(ARGV[2])
local enroll    = ARGV[3] == '1'
local rate      = tonumber(ARGV[4])
local interval  = tonumber(ARGV[5])
local burst     = tonumber(ARGV[6])
local maxActive = tonumber(ARGV[7])
local tokenTtl  = tonumber(ARGV[8])
local queueTtl  = tonumber(ARGV[9])
local PRUNE_LIMIT = 100

-- 1. Expire admissions whose TTL passed, so maxActive frees up.
redis.call('ZREMRANGEBYSCORE', active, '-inf', now)

-- 2. Drop waiters that stopped polling, so they stop holding places in line. Bounded per call, so the requesting
--    visitor is checked on its own: an expired place must not be revived just because it fell outside the batch.
if queueTtl > 0 then
  local deadline = now - queueTtl
  local idle = redis.call('ZRANGEBYSCORE', seen, '-inf', deadline, 'LIMIT', 0, PRUNE_LIMIT)
  if #idle > 0 then
    redis.call('ZREM', queue, unpack(idle))
    redis.call('ZREM', seen, unpack(idle))
  end
  local lastSeen = redis.call('ZSCORE', seen, visitor)
  if lastSeen and tonumber(lastSeen) <= deadline then
    redis.call('ZREM', queue, visitor)
    redis.call('ZREM', seen, visitor)
  end
end

-- 3. Already admitted: re-report so a lost response never loses the admission.
local expiresAt = redis.call('ZSCORE', active, visitor)
if expiresAt then
  return { 'ADMITTED', -1, redis.call('ZCARD', queue), tonumber(expiresAt) }
end

-- 4. Locate in line, enrolling if asked.
local position = redis.call('ZRANK', queue, visitor)
if not position then
  if not enroll then
    return { 'NOT_IN_QUEUE', -1, redis.call('ZCARD', queue), 0 }
  end
  local seq = redis.call('HINCRBY', meta, 'seq', 1)
  redis.call('ZADD', queue, seq, visitor)
  position = redis.call('ZRANK', queue, visitor)
end
redis.call('ZADD', seen, now, visitor)

-- 5. Refill the token bucket lazily. A fresh room starts full so the first `burst` visitors enter at once.
local tokens   = tonumber(redis.call('HGET', meta, 'tokens') or burst)
local refillAt = tonumber(redis.call('HGET', meta, 'refillAt') or now)
local elapsed  = math.floor((now - refillAt) / interval)
if elapsed > 0 then
  tokens   = math.min(burst, tokens + elapsed * rate)
  refillAt = refillAt + elapsed * interval
end

-- 6. Admit when the visitor's place is inside the budget. Visitors ahead keep their places (the budget shrinks
--    by one per admission), so nobody can be overtaken out of the bucket; they just have to poll to collect.
local budget = tokens
if maxActive > 0 then
  budget = math.min(budget, maxActive - redis.call('ZCARD', active))
end
if position < budget then
  redis.call('HSET', meta, 'tokens', tokens - 1, 'refillAt', refillAt)
  redis.call('ZREM', queue, visitor)
  redis.call('ZREM', seen, visitor)
  local admittedUntil = now + tokenTtl
  redis.call('ZADD', active, admittedUntil, visitor)
  return { 'ADMITTED', -1, redis.call('ZCARD', queue), admittedUntil }
end

redis.call('HSET', meta, 'tokens', tokens, 'refillAt', refillAt)
return { 'WAITING', position, redis.call('ZCARD', queue), 0 }
