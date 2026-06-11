--[[
  sliding_window_peek.lua — READ-ONLY sliding-window rate-limit check for Redis.

  This is the read half of the original sliding_window.lua, split so that
  outcome-aware throttling (LOGIN) can peek at the current estimate WITHOUT
  consuming a slot. The matching write half is sliding_window_incr.lua.

  Implements the same Cloudflare weighted-interpolation estimate, but performs
  NO writes (no INCR, no EXPIRE):

    position  = now_ms mod window_ms
    weight    = 1 − position / window_ms
    estimated = prev_count × weight + curr_count
    allow if  estimated < limit

  KEYS
    [1]  current window counter key
    [2]  previous window counter key

  ARGV
    [1]  limit      — maximum requests allowed per window (integer)
    [2]  window_ms  — window duration in milliseconds (integer)
    [3]  now_ms     — current epoch time in milliseconds (integer)

  Returns (multi-bulk / array) — same shape as sliding_window.lua
    [1]  allowed      — 1 if under the limit, 0 if at/over it
    [2]  remaining    — floor of requests remaining (≥ 0; 0 when denied)
    [3]  reset_after  — milliseconds until the window rolls over (0 when allowed)
]]

local curr_key  = KEYS[1]
local prev_key  = KEYS[2]
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now_ms    = tonumber(ARGV[3])

-- ── 1. Read current counter state (no writes) ─────────────────────────────────
local prev_count = tonumber(redis.call('GET', prev_key)) or 0
local curr_count = tonumber(redis.call('GET', curr_key)) or 0

-- ── 2. Compute weighted estimate of requests in the sliding window ────────────
local position_ms = now_ms % window_ms
local weight      = 1 - position_ms / window_ms
local estimated   = prev_count * weight + curr_count

-- ── 3. Decide without mutating any counter ────────────────────────────────────
if estimated < limit then
    return {1, math.floor(limit - estimated), 0}
else
    local reset_after = math.ceil(window_ms - position_ms)
    return {0, 0, reset_after}
end
