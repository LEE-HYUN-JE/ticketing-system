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

-- event/user는 대기열에 한 번만 들어가야 한다.
-- 이미 reverse index가 있으면 새 token을 만들지 않고 기존 token을 그대로 돌려준다.
local existingToken = redis.call('GET', userTokenKey)
if existingToken and existingToken ~= '' then
  return existingToken
end

-- 신규 진입은 token mapping, reverse index, waiting ZSET, scheduler event registry를 한 번에 기록한다.
-- 이 script가 Redis 단일 명령처럼 실행되므로 동시 진입 요청도 하나의 상태로 수렴한다.
redis.call('HSET', tokenKey, 'eventId', eventId, 'userId', userId, 'createdAt', createdAt)
redis.call('EXPIRE', tokenKey, ttlSeconds)
redis.call('SET', userTokenKey, token, 'EX', ttlSeconds)
redis.call('SADD', queueEventsKey, eventId)
redis.call('ZADD', waitingKey, 'NX', score, userId)

return token
