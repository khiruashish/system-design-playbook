-- Extend the lease only if we still own it (compare-and-expire). KEYS[1] = lease key; ARGV: owner, ttl_ms
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 0
