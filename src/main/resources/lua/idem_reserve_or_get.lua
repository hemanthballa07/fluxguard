--[[
  idem_reserve_or_get.lua — Atomic idempotency reserve-or-get for Redis.

  Reserves a slot for a not-yet-seen idempotency key, or returns the cached
  decision for a key that was already resolved. The read-modify-write runs in a
  single atomic Redis operation so concurrent callers see a consistent state.

  KEYS
    [1]  idempotency key (e.g. "rl:idem:{subject}:{configKey}:{idem}")

  ARGV
    [1]  reserve_ttl_ms      — TTL for the "pending" reservation (integer)

  RETURNS (always a non-nil array)
    {1}            FIRST      — caller runs the bucket and stores the decision
    {2}            CONCURRENT — another caller holds the reservation
    {0, value}     HIT        — value is "allowed:remaining:resetAfterMs"
]]

local v = redis.call('GET', KEYS[1])
if not v then
  redis.call('SET', KEYS[1], 'pending', 'PX', tonumber(ARGV[1]))
  return {1}            -- FIRST: caller runs the bucket and stores
end
if v == 'pending' then return {2} end   -- CONCURRENT
return {0, v}            -- HIT: v is "allowed:remaining:resetAfterMs"
