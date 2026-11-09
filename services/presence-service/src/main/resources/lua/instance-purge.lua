-- KEYS: instance sessions (zset), instances, alive
-- ARGV: instanceId
-- Gỡ mọi session của instance như disconnect, trả danh sách userId vừa về 0 session
-- Khóa alive đã có lại (heartbeat đến sau lúc phát hiện chết) thì không làm gì, trả rỗng
if redis.call('EXISTS', KEYS[3]) == 1 then
  return {}
end
local offline = {}
for _, sid in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
  local v = redis.call('GET', 'presence:session:' .. sid)
  if v then
    local u = string.match(v, '^([^|]*)|')
    redis.call('DEL', 'presence:session:' .. sid)
    local sessions = 'presence:user:' .. u .. ':sessions'
    redis.call('SREM', sessions, sid)
    if redis.call('SCARD', sessions) == 0 then
      redis.call('DEL', 'presence:status:' .. u)
      offline[#offline + 1] = u
    end
  end
end
redis.call('DEL', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return offline
