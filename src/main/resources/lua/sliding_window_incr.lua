--[[
  sliding_window_incr.lua — INCREMENT-ONLY sliding-window counter bump for Redis.

  This is the write half of the original sliding_window.lua, split so that
  outcome-aware throttling (LOGIN) can record a failure by incrementing the
  current-window counter independently of the read-only peek check
  (sliding_window_peek.lua).

  Increments the current-window counter and (re)sets its TTL to two windows so
  the previous window remains readable by the peek script.

  KEYS
    [1]  current window counter key
    [2]  previous window counter key (unused; present for KEYS-shape parity)

  ARGV
    [1]  limit      — unused; present for ARGV-shape parity
    [2]  window_ms  — window duration in milliseconds (integer)
    [3]  now_ms     — unused; present for ARGV-shape parity

  Returns (multi-bulk / array)
    [1]  count — the new current-window counter value after the increment
]]

local curr_key  = KEYS[1]
local window_ms = tonumber(ARGV[2])

local n = redis.call('INCR', curr_key)
redis.call('EXPIRE', curr_key, math.ceil(window_ms * 2 / 1000))
return {n}
