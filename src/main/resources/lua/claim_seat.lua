local activeKey = KEYS[1]
local seatKey = KEYS[2]
local reservationUserKey = KEYS[3]
local idempotencyKey = KEYS[4]

local seatId = ARGV[1]
local userId = ARGV[2]
local reservedAt = ARGV[3]
local idempotencyTtlSeconds = tonumber(ARGV[4])

-- 같은 Idempotency-Key로 다시 들어온 요청은 좌석 선점을 재시도하지 않고 최초 결과를 재생한다.
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

-- 성공뿐 아니라 실패 결과도 저장한다.
-- 클라이언트 재시도 시 같은 key는 같은 비즈니스 응답으로 수렴해야 하기 때문이다.
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

-- 한 사용자는 한 이벤트에서 하나의 좌석만 가질 수 있다.
-- 다른 Idempotency-Key로 다른 좌석을 다시 시도해도 기존 좌석을 반환한다.
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

-- 여기까지 통과한 요청만 실제 좌석 점유와 사용자 예약 hash를 기록한다.
redis.call('SET', seatKey, userId)
redis.call('HSET', reservationUserKey,
  'seatId', seatId,
  'status', 'RESERVED',
  'reservedAt', reservedAt
)

return store('RESERVED', seatId, 'Reserved')
