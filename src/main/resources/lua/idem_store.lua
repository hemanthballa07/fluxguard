--[[
  idem_store.lua — Atomic idempotency decision store for Redis.

  Overwrites the "pending" reservation written by idem_reserve_or_get.lua with
  the final encoded decision, applying a TTL so the cached result expires.

  KEYS
    [1]  idempotency key (e.g. "rl:idem:{subject}:{configKey}:{idem}")

  ARGV
    [1]  decision_str        — "allowed:remaining:resetAfterMs"
    [2]  ttl_ms              — TTL for the cached decision (integer)

  RETURNS
    {1}            stored
]]

redis.call('SET', KEYS[1], ARGV[1], 'PX', tonumber(ARGV[2]))
return {1}
