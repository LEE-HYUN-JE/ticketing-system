package com.example.ticketing.queue.infrastructure;

import com.example.ticketing.queue.domain.QueuePosition;
import com.example.ticketing.queue.domain.QueueTokenMapping;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Repository;

@Repository
public class RedisQueueRepository {

    private static final String EVENT_ID = "eventId";
    private static final String USER_ID = "userId";
    private static final String CREATED_AT = "createdAt";

    private final StringRedisTemplate redisTemplate;
    private final QueueRedisKeys keys;
    private final DefaultRedisScript<String> registerQueueEntryScript;

    public RedisQueueRepository(StringRedisTemplate redisTemplate, QueueRedisKeys keys) {
        this.redisTemplate = redisTemplate;
        this.keys = keys;
        this.registerQueueEntryScript = new DefaultRedisScript<>();
        this.registerQueueEntryScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/register_queue_entry.lua")));
        this.registerQueueEntryScript.setResultType(String.class);
    }

    /**
     * event/user reverse index로 이미 발급된 queue token을 찾는다.
     * token hash까지 다시 확인해 stale reverse index가 잘못된 사용자에게 재사용되지 않게 한다.
     */
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

    /**
     * queue 진입 hot path를 Lua script 하나로 처리한다.
     * token mapping, reverse index, waiting ZSET, event registry를 원자적으로 갱신해 동시 중복 진입을 하나의 token으로 모은다.
     */
    public String registerQueueEntry(String token, String eventId, String userId, Instant createdAt, Duration ttl, double score) {
        // 대기열 진입은 token hash, event/user 역색인, waiting ZSET, event registry가 함께 갱신되어야 한다.
        // Lua로 묶어 동일 event/user 동시 진입을 하나의 queue token으로 수렴시킨다.
        String result = redisTemplate.execute(
                registerQueueEntryScript,
                List.of(
                        keys.queueUserToken(eventId, userId),
                        keys.queueToken(token),
                        keys.waiting(eventId),
                        keys.queueEvents()
                ),
                eventId,
                userId,
                token,
                createdAt.toString(),
                Long.toString(ttl.toSeconds()),
                Double.toString(score)
        );
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Queue entry registration failed");
        }
        return result;
    }

    /**
     * polling token을 event/user 정보로 변환한다.
     * 이 mapping이 사라졌다면 token TTL이 만료된 것으로 보고 상태 조회는 EXPIRED로 수렴한다.
     */
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

    /**
     * waiting ZSET의 rank를 1-based 순번으로 변환한다.
     * rank가 없으면 사용자가 이미 active로 전환됐거나 token이 만료된 상태일 수 있어 Optional.empty()로 반환한다.
     */
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

    public Set<String> findQueueEvents() {
        Set<String> events = redisTemplate.opsForSet().members(keys.queueEvents());
        return events == null ? Set.of() : events;
    }

    public long countWaiting(String eventId) {
        Long count = redisTemplate.opsForZSet().size(keys.waiting(eventId));
        return count == null ? 0L : count;
    }

    public long removeExpiredActiveUsers(String eventId, long nowEpochMillis) {
        Long removed = redisTemplate.opsForZSet().removeRangeByScore(keys.activeUsers(eventId), 0, nowEpochMillis);
        return removed == null ? 0L : removed;
    }

    public long countTrackedActiveUsers(String eventId) {
        Long count = redisTemplate.opsForZSet().size(keys.activeUsers(eventId));
        return count == null ? 0L : count;
    }

    public void incrementRegistered() {
        redisTemplate.opsForValue().increment(keys.metricsRegistered());
    }

    public void incrementAdmitted(long count) {
        if (count > 0) {
            redisTemplate.opsForValue().increment(keys.metricsAdmitted(), count);
        }
    }

    public void incrementExpiredLookup() {
        redisTemplate.opsForValue().increment(keys.metricsExpiredLookup());
    }

    public long metricValue(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing queue token field: " + key);
        }
        return value.toString();
    }
}
