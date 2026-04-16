--[[
  token_bucket.lua — Atomic token-bucket rate-limit check for Redis.

  Called by LuaScriptExecutor via EVALSHA so the read-modify-write is
  performed in a single atomic operation (no WATCH/MULTI needed).

  KEYS
    [1]  bucket key (e.g. "rl:api-key-abc:/api/v1/search")

  ARGV
    [1]  capacity            — max tokens the bucket can hold (integer)
    [2]  refill_rate         — tokens added per second (integer)
    [3]  now_millis          — current epoch time in milliseconds (integer)
    [4]  tokens_requested    — number of tokens to consume (usually 1)

  Returns (multi-bulk / array)
    [1]  allowed      — 1 if the request is permitted, 0 if denied
    [2]  remaining    — floor of tokens remaining after this decision (>=0)
    [3]  reset_after  — milliseconds until one token is available (0 when allowed)
]]

local key              = KEYS[1]
local capacity         = tonumber(ARGV[1])
local refill_rate      = tonumber(ARGV[2])
local now_ms           = tonumber(ARGV[3])
local requested        = tonumber(ARGV[4])

-- ── 1. Read current bucket state from Redis ──────────────────────────────────
local bucket      = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens      = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now_ms

-- ── 2. Refill tokens based on elapsed time ──────────────────────────────────
local elapsed_ms   = now_ms - last_refill
if elapsed_ms < 0 then elapsed_ms = 0 end
local added        = elapsed_ms * refill_rate / 1000
local new_tokens   = math.min(capacity, tokens + added)

-- ── 3. Decide ────────────────────────────────────────────────────────────────
local allowed      = 0
local remaining    = math.floor(new_tokens)
local reset_after  = 0

if new_tokens >= requested then
    allowed    = 1
    new_tokens = new_tokens - requested
    remaining  = math.floor(new_tokens)
else
    local deficit = requested - new_tokens
    reset_after   = math.ceil(deficit / refill_rate * 1000)
end

-- ── 4. Persist updated state with a self-expiring TTL ────────────────────────
-- TTL is set to 2× the time required to refill from empty to full, so stale
-- keys for inactive clients are evicted automatically.
local ttl_seconds = math.ceil(capacity / refill_rate) * 2

redis.call('HSET', key, 'tokens', tostring(new_tokens), 'last_refill', tostring(now_ms))
redis.call('EXPIRE', key, ttl_seconds)

-- ── 5. Return ────────────────────────────────────────────────────────────────
return {allowed, remaining, reset_after}
