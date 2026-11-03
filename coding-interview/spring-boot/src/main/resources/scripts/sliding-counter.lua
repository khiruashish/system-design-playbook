-- Sliding COUNTER (approximate, O(1) memory). KEYS[1] = current window counter, KEYS[2] = previous window counter.
-- ARGV: now_ms, window_ms, limit
local now, window, limit = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local elapsed = now % window
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local previous = tonumber(redis.call('GET', KEYS[2]) or '0')
local estimated = previous * (1 - elapsed / window) + current
if estimated >= limit then
  return {0, 0, window - elapsed}
end
redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], window * 2)                               -- keep it while it is the "previous" window
return {1, math.max(0, math.floor(limit - estimated - 1)), 0}
