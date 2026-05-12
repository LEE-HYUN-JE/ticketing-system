local waitingKey = KEYS[1]
local activePrefix = KEYS[2]
local activeUsersKey = KEYS[3]

local limit = tonumber(ARGV[1])
local ttlSeconds = tonumber(ARGV[2])
local nowEpochMillis = tonumber(ARGV[3])
local activeTtlMillis = ttlSeconds * 1000

if limit <= 0 then
  return {}
end

-- score가 낮은 사용자부터 꺼내므로 먼저 대기열에 들어온 사용자가 먼저 active가 된다.
local users = redis.call('ZRANGE', waitingKey, 0, limit - 1)
local admitted = {}

for _, userId in ipairs(users) do
  -- ZREM이 성공한 사용자만 active로 전환한다.
  -- 같은 tick이나 다른 scheduler가 겹쳐도 한 사용자가 중복 입장하지 않게 하기 위한 확인이다.
  local removed = redis.call('ZREM', waitingKey, userId)
  if removed == 1 then
    redis.call('SET', activePrefix .. userId, tostring(nowEpochMillis), 'PX', activeTtlMillis)
    redis.call('ZADD', activeUsersKey, nowEpochMillis + activeTtlMillis, userId)
    table.insert(admitted, userId)
  end
end

return admitted
