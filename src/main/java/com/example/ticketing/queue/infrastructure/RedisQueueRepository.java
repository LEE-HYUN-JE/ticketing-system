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

/**
 * Queue 기능이 사용하는 Redis 자료구조 접근을 캡슐화한다.
 *
 * <p>이 repository는 단순 CRUD 계층이 아니라 대기열 시스템의 실시간 상태 저장소 경계다.
 * queue token, waiting ZSET, active TTL key, event registry, 운영 메트릭 counter를 Redis에 저장하고 조회한다.</p>
 */
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
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @return 동일 event/user로 이미 발급된 유효 queue token
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

    /**
     * queue token Hash와 event/user 역색인을 저장한다.
     *
     * <p>현재 hot path는 {@link #registerQueueEntry(String, String, String, Instant, Duration, double)}를 사용하지만,
     * 테스트나 단순 저장 경계가 필요할 때 token mapping만 직접 기록할 수 있도록 남겨둔다.</p>
     */
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
     *
     * @param token 신규 진입 시 사용할 후보 queue token
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @param createdAt token 생성 시각
     * @param ttl queue token과 역색인의 유지 시간
     * @param score waiting ZSET 정렬 score
     * @return 신규 token 또는 기존 event/user에 이미 발급된 token
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
     *
     * @param token 클라이언트가 보낸 queue token
     * @return token이 아직 유효하면 event/user mapping
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

    /**
     * 테스트나 보조 경로에서 waiting ZSET에 사용자를 직접 추가한다.
     * 실제 API hot path에서는 중복 제어까지 포함된 Lua script 경로를 우선 사용한다.
     */
    public void addWaitingUserIfAbsent(String eventId, String userId, double score) {
        redisTemplate.opsForSet().add(keys.queueEvents(), eventId);
        if (redisTemplate.opsForZSet().score(keys.waiting(eventId), userId) == null) {
            redisTemplate.opsForZSet().add(keys.waiting(eventId), userId, score);
        }
    }

    /**
     * waiting ZSET의 rank를 1-based 순번으로 변환한다.
     * rank가 없으면 사용자가 이미 active로 전환됐거나 token이 만료된 상태일 수 있어 Optional.empty()로 반환한다.
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @return 사용자가 아직 waiting 상태라면 1-based 순번과 전체 대기 인원
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

    /**
     * active admission TTL을 초 단위로 조회한다.
     * Redis TTL 규칙에 따라 key가 없으면 -2가 반환되어 EXPIRED/NOT_ACTIVE 판단에 사용된다.
     */
    public long getActiveTtlSeconds(String eventId, String userId) {
        Long seconds = redisTemplate.getExpire(keys.active(eventId, userId), TimeUnit.SECONDS);
        return seconds == null ? -2 : seconds;
    }

    /**
     * 스케줄러가 처리할 eventId 목록을 반환한다.
     * Redis 전체 keyspace를 훑는 {@code KEYS waiting:*} 대신 queue-events Set을 사용한다.
     */
    public Set<String> findQueueEvents() {
        Set<String> events = redisTemplate.opsForSet().members(keys.queueEvents());
        return events == null ? Set.of() : events;
    }

    public long countWaiting(String eventId) {
        Long count = redisTemplate.opsForZSet().size(keys.waiting(eventId));
        return count == null ? 0L : count;
    }

    /**
     * active-users ZSET에서 만료 시각이 지난 관찰 항목을 제거한다.
     * 실제 권한 만료는 active TTL key가 담당하고, 이 메서드는 메트릭 정확도를 위한 정리 작업이다.
     */
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
