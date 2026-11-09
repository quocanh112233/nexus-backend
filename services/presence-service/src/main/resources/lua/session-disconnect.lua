-- KEYS: session, closed tombstone
-- ARGV: sessionId, tombstone ttl (ms)
-- Trả userId nếu user vừa về 0 session, ngược lại nil; luôn ghi tombstone để connect đến muộn bị bỏ
redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
local v = redis.call('GET', KEYS[1])
if not v then
  return false
end
local u, i = string.match(v, '^([^|]*)|(.*)$')
redis.call('DEL', KEYS[1])
redis.call('ZREM', 'presence:instance:' .. i .. ':sessions', ARGV[1])
local sessions = 'presence:user:' .. u .. ':sessions'
redis.call('SREM', sessions, ARGV[1])
if redis.call('SCARD', sessions) > 0 then
  return false
end
redis.call('DEL', 'presence:status:' .. u)
return u
