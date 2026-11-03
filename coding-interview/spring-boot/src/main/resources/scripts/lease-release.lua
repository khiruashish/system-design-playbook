-- Delete the lease only if we still own it. KEYS[1] = lease key; ARGV[1] = owner
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
