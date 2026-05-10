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

local users = redis.call('ZRANGE', waitingKey, 0, limit - 1)
local admitted = {}

for _, userId in ipairs(users) do
  local removed = redis.call('ZREM', waitingKey, userId)
  if removed == 1 then
    redis.call('SET', activePrefix .. userId, tostring(nowEpochMillis), 'PX', activeTtlMillis)
    redis.call('ZADD', activeUsersKey, nowEpochMillis + activeTtlMillis, userId)
    table.insert(admitted, userId)
  end
end

return admitted

