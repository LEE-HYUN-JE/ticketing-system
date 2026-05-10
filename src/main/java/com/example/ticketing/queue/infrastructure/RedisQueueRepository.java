package com.example.ticketing.queue.infrastructure;

import com.example.ticketing.queue.domain.QueueModels.QueuePosition;
import com.example.ticketing.queue.domain.QueueModels.QueueTokenMapping;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisQueueRepository {

    private static final String EVENT_ID = "eventId";
    private static final String USER_ID = "userId";
    private static final String CREATED_AT = "createdAt";

    private final StringRedisTemplate redisTemplate;
    private final QueueRedisKeys keys;

    public RedisQueueRepository(StringRedisTemplate redisTemplate, QueueRedisKeys keys) {
        this.redisTemplate = redisTemplate;
        this.keys = keys;
    }

    public Optional<String> findExistingToken(String eventId, String userId) {
        String token = redisTemplate.opsForValue().get(keys.queueUserToken(eventId, userId));
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return findTokenMapping(token)
                .filter(mapping -> mapping.eventId().equals(eventId))
                .filter(mapping -> mapping.userId().equals(userId))
                .map(QueueTokenMapping::token);
    }

    public void saveTokenMapping(String token, String eventId, String userId, Instant createdAt, Duration ttl) {
        redisTemplate.opsForHash().putAll(keys.queueToken(token), Map.of(
                EVENT_ID, eventId,
                USER_ID, userId,
                CREATED_AT, createdAt.toString()
        ));
        redisTemplate.expire(keys.queueToken(token), ttl);
        redisTemplate.opsForValue().set(keys.queueUserToken(eventId, userId), token, ttl);
    }

    public Optional<QueueTokenMapping> findTokenMapping(String token) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(keys.queueToken(token));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new QueueTokenMapping(
                token,
                value(values, EVENT_ID),
                value(values, USER_ID),
                Instant.parse(value(values, CREATED_AT))
        ));
    }

    public void addWaitingUserIfAbsent(String eventId, String userId, double score) {
        redisTemplate.opsForSet().add(keys.queueEvents(), eventId);
        if (redisTemplate.opsForZSet().score(keys.waiting(eventId), userId) == null) {
            redisTemplate.opsForZSet().add(keys.waiting(eventId), userId, score);
        }
    }

    public Optional<QueuePosition> getWaitingPosition(String eventId, String userId) {
        Long zeroBasedRank = redisTemplate.opsForZSet().rank(keys.waiting(eventId), userId);
        if (zeroBasedRank == null) {
            return Optional.empty();
        }
        Long totalWaiting = redisTemplate.opsForZSet().size(keys.waiting(eventId));
        return Optional.of(new QueuePosition(zeroBasedRank + 1, totalWaiting == null ? 0 : totalWaiting));
    }

    public long tokenTtlSeconds(String token) {
        Long seconds = redisTemplate.getExpire(keys.queueToken(token), TimeUnit.SECONDS);
        return seconds == null ? -2 : seconds;
    }

    public long getActiveTtlSeconds(String eventId, String userId) {
        Long seconds = redisTemplate.getExpire(keys.active(eventId, userId), TimeUnit.SECONDS);
        return seconds == null ? -2 : seconds;
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing queue token field: " + key);
        }
        return value.toString();
    }
}
