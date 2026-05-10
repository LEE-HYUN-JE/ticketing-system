local activeKey = KEYS[1]
local seatKey = KEYS[2]
local reservationUserKey = KEYS[3]
local idempotencyKey = KEYS[4]

local seatId = ARGV[1]
local userId = ARGV[2]
local reservedAt = ARGV[3]
local idempotencyTtlSeconds = tonumber(ARGV[4])

local function replay()
  local storedStatus = redis.call('HGET', idempotencyKey, 'status')
  if storedStatus then
    return {
      storedStatus,
      redis.call('HGET', idempotencyKey, 'seatId') or '',
      redis.call('HGET', idempotencyKey, 'message') or ''
    }
  end
  return nil
end

local function store(status, resultSeatId, message)
  redis.call('HSET', idempotencyKey,
    'status', status,
    'seatId', resultSeatId,
    'message', message,
    'requestSeatId', seatId,
    'createdAt', reservedAt
  )
  redis.call('EXPIRE', idempotencyKey, idempotencyTtlSeconds)
  return {status, resultSeatId, message}
end

local stored = replay()
if stored then
  return stored
end

if redis.call('EXISTS', activeKey) == 0 then
  return store('NOT_ACTIVE', '', 'Active admission is required')
end

local existingSeat = redis.call('HGET', reservationUserKey, 'seatId')
if existingSeat then
  if existingSeat == seatId then
    return store('RESERVED', existingSeat, 'Already reserved by same user')
  end
  return store('ALREADY_RESERVED', existingSeat, 'User already reserved another seat')
end

local currentOwner = redis.call('GET', seatKey)
if currentOwner then
  return store('SEAT_ALREADY_TAKEN', seatId, 'Seat already taken')
end

redis.call('SET', seatKey, userId)
redis.call('HSET', reservationUserKey,
  'seatId', seatId,
  'status', 'RESERVED',
  'reservedAt', reservedAt
)

return store('RESERVED', seatId, 'Reserved')
