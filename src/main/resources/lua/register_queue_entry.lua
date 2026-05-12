local userTokenKey = KEYS[1]
local tokenKey = KEYS[2]
local waitingKey = KEYS[3]
local queueEventsKey = KEYS[4]

local eventId = ARGV[1]
local userId = ARGV[2]
local token = ARGV[3]
local createdAt = ARGV[4]
local ttlSeconds = tonumber(ARGV[5])
local score = tonumber(ARGV[6])

local existingToken = redis.call('GET', userTokenKey)
if existingToken and existingToken ~= '' then
  return existingToken
end

redis.call('HSET', tokenKey, 'eventId', eventId, 'userId', userId, 'createdAt', createdAt)
redis.call('EXPIRE', tokenKey, ttlSeconds)
redis.call('SET', userTokenKey, token, 'EX', ttlSeconds)
redis.call('SADD', queueEventsKey, eventId)
redis.call('ZADD', waitingKey, 'NX', score, userId)

return token
