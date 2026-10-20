-- token_bucket.lua — an ATOMIC per-tenant token-bucket rate limiter, evaluated server-side in Redis so concurrent
-- requests can't race. KEYS[1] = the tenant's bucket key. ARGV: capacity, refill_per_sec, now_ms, requested(=1).
-- State stored in a hash: tokens (current) + ts (last refill, ms). Refill lazily by elapsed time, then try to spend.
local key   = KEYS[1]
local cap   = tonumber(ARGV[1])
local rate  = tonumber(ARGV[2])          -- tokens added per second
local now   = tonumber(ARGV[3])          -- current time in ms
local want  = tonumber(ARGV[4])

local b = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(b[1])
local ts     = tonumber(b[2])
if tokens == nil then tokens = cap; ts = now end     -- first request: full bucket

-- refill for the time elapsed since the last update
local delta = math.max(0, now - ts) / 1000.0
tokens = math.min(cap, tokens + delta * rate)
ts = now

local allowed = 0
if tokens >= want then tokens = tokens - want; allowed = 1 end

redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, 60000)                    -- idle buckets expire so we don't leak memory per tenant
return allowed
