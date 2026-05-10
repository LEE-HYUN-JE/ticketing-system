local activeKey = KEYS[1]
local seatKey = KEYS[2]
local reservationUserKey = KEYS[3]

local seatId = ARGV[1]
local userId = ARGV[2]
local reservedAt = ARGV[3]

if redis.call('EXISTS', activeKey) == 0 then
  return {'NOT_ACTIVE', '', 'Active admission is required'}
end

local existingSeat = redis.call('HGET', reservationUserKey, 'seatId')
if existingSeat then
  if existingSeat == seatId then
    return {'RESERVED', existingSeat, 'Already reserved by same user'}
  end
  return {'ALREADY_RESERVED', existingSeat, 'User already reserved another seat'}
end

local currentOwner = redis.call('GET', seatKey)
if currentOwner then
  return {'SEAT_ALREADY_TAKEN', seatId, 'Seat already taken'}
end

redis.call('SET', seatKey, userId)
redis.call('HSET', reservationUserKey,
  'seatId', seatId,
  'status', 'RESERVED',
  'reservedAt', reservedAt
)

return {'RESERVED', seatId, 'Reserved'}

