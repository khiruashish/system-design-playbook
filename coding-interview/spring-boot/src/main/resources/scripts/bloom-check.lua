-- KEYS[1] = bitmap; ARGV = bit positions. 0 = definitely absent, 1 = possibly present.
for i = 1, #ARGV do
  if redis.call('GETBIT', KEYS[1], ARGV[i]) == 0 then return 0 end
end
return 1
