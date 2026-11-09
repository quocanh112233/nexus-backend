-- KEYS: session, user sessions, instance sessions (zset), user status, closed tombstone,
--       instances, alive
-- ARGV: sessionId, userId, instanceId, connectedAt (ms), alive ttl (ms)
-- Trả 1 nếu user từ 0 lên 1 session, ngược lại 0; session đã có hoặc đã đóng thì không làm gì
if redis.call('EXISTS', KEYS[5]) == 1 or redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('SET', KEYS[1], ARGV[2] .. '|' .. ARGV[3])
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])
redis.call('SADD', KEYS[6], ARGV[3])
redis.call('SET', KEYS[7], '1', 'PX', ARGV[5], 'NX')
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SET', KEYS[4], 'ONLINE')
if redis.call('SCARD', KEYS[2]) == 1 then
  return 1
end
return 0
