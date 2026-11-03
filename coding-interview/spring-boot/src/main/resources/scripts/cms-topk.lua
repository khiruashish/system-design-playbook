-- Count-Min Sketch + top-K in one atomic call.
-- KEYS[1] = sketch hash (field "row:col"), KEYS[2] = top-K sorted set
-- ARGV: item, k, ttl_ms, col_row0, col_row1, ...
local estimate = nil
for i = 4, #ARGV do
  local v = redis.call('HINCRBY', KEYS[1], (i - 4) .. ':' .. ARGV[i], 1)
  if estimate == nil or v < estimate then estimate = v end         -- CMS estimate = min over rows
end
redis.call('ZADD', KEYS[2], estimate, ARGV[1])
local k, size = tonumber(ARGV[2]), redis.call('ZCARD', KEYS[2])
if size > k then redis.call('ZREMRANGEBYRANK', KEYS[2], 0, size - k - 1) end   -- keep only the K largest
redis.call('PEXPIRE', KEYS[1], ARGV[3])
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return estimate
