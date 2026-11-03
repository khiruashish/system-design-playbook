-- Sliding LOG (exact). KEYS[1] = per-user sorted set of accepted request timestamps.
-- ARGV: now_ms, window_ms, limit, unique_member
local now, window, limit = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)          -- drop expired entries
local count = redis.call('ZCARD', KEYS[1])
if count < limit then
  redis.call('ZADD', KEYS[1], now, ARGV[4])
  redis.call('PEXPIRE', KEYS[1], window)
  return {1, limit - count - 1, 0}                                       -- allowed, remaining, retry-after
end
local oldest = tonumber(redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')[2])
return {0, 0, oldest + window - now}                                     -- blocked until the oldest entry expires
