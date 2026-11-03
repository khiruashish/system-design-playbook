-- KEYS[1] = bitmap; ARGV = bit positions. Returns 1 if any bit was newly set (i.e. the item was probably new).
local changed = 0
for i = 1, #ARGV do
  if redis.call('SETBIT', KEYS[1], ARGV[i], 1) == 0 then changed = 1 end
end
return changed
