-- KEYS[1] = global counts zset; ARGV: term, k, prefix1, prefix2, ...  (prefix keys are passed in ARGV and
-- built from the term, so this script is for single-node Redis; in a cluster, hash-tag them or shard by prefix)
local score = redis.call('ZINCRBY', KEYS[1], 1, ARGV[1])
local k = tonumber(ARGV[2])
for i = 3, #ARGV do
  local key = 'ac:prefix:' .. ARGV[i]
  redis.call('ZADD', key, score, ARGV[1])                        -- update this term's score under every prefix
  local size = redis.call('ZCARD', key)
  if size > k then redis.call('ZREMRANGEBYRANK', key, 0, size - k - 1) end   -- keep the top K per prefix
end
return score
