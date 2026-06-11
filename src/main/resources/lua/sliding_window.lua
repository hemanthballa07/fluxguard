--[[
  sliding_window.lua — Atomic two-counter sliding-window rate-limit check for Redis.

  Called by LuaScriptExecutor via EVALSHA so the read-modify-write is performed in
  a single atomic operation. Implements the Cloudflare weighted-interpolation approach:

    position  = now_ms mod window_ms
    weight    = 1 − position / window_ms
    estimated = prev_count × weight + curr_count
    allow if  estimated + 1 ≤ limit

  KEYS
    [1]  current window counter key  (e.g. "sw:rl:client:/path:42")
    [2]  previous window counter key (e.g. "sw:rl:client:/path:41")

  ARGV
    [1]  limit      — maximum requests allowed per window (integer)
    [2]  window_ms  — window duration in milliseconds (integer)
    [3]  now_ms     — current epoch time in milliseconds (integer)

  Returns (multi-bulk / array)
    [1]  allowed      — 1 if the request is permitted, 0 if denied
    [2]  remaining    — floor of requests remaining after this decision (≥ 0)
    [3]  reset_after  — milliseconds until the window rolls over (0 when allowed)
]]

local curr_key  = KEYS[1]
local prev_key  = KEYS[2]
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now_ms    = tonumber(ARGV[3])

-- ── 1. Read current counter state ─────────────────────────────────────────────
local prev_count = tonumber(redis.call('GET', prev_key)) or 0
local curr_count = tonumber(redis.call('GET', curr_key)) or 0

-- ── 2. Compute weighted estimate of requests in the sliding window ─────────────
local position_ms = now_ms % window_ms
local weight      = 1 - position_ms / window_ms
local estimated   = prev_count * weight + curr_count

-- ── 3. Decide ─────────────────────────────────────────────────────────────────
if estimated + 1 <= limit then
    -- Allow: increment current window counter and set TTL
    local ttl_seconds = math.ceil(window_ms * 2 / 1000)
    redis.call('INCR', curr_key)
    redis.call('EXPIRE', curr_key, ttl_seconds)

    local remaining = math.floor(limit - estimated - 1)
    return {1, remaining, 0}
else
    -- Deny: return milliseconds until the window rotates
    local reset_after = math.ceil(window_ms - position_ms)
    return {0, 0, reset_after}
end
