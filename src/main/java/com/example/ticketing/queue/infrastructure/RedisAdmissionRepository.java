package com.example.ticketing.queue.infrastructure;

import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Repository;

/**
 * waiting queue에서 active admission으로 이동시키는 Redis 경계다.
 *
 * <p>입장 허가는 여러 Redis key를 함께 갱신한다. 이 repository는
 * {@code admit_waiting_users.lua}를 통해 waiting ZSET 제거, active TTL key 생성,
 * active 추적 ZSET 기록을 하나의 원자 단위로 실행한다.</p>
 */
@Repository
public class RedisAdmissionRepository {

    private final StringRedisTemplate redisTemplate;
    private final QueueRedisKeys keys;
    private final DefaultRedisScript<List> admitScript;

    public RedisAdmissionRepository(StringRedisTemplate redisTemplate, QueueRedisKeys keys) {
        this.redisTemplate = redisTemplate;
        this.keys = keys;
        this.admitScript = new DefaultRedisScript<>();
        this.admitScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/admit_waiting_users.lua")));
        this.admitScript.setResultType(List.class);
    }

    /**
     * waiting ZSET에서 가장 오래 기다린 사용자들을 active admission으로 이동시킨다.
     * Lua script가 ZREM 성공 여부를 확인하므로 여러 scheduler가 겹쳐도 한 사용자가 중복 입장하지 않는다.
     *
     * @param eventId 입장 허가를 진행할 이벤트 식별자
     * @param limit 이번 tick에서 active로 전환할 최대 사용자 수
     * @param activeTtlSeconds active admission TTL
     * @param now active 전환 기준 시각
     * @return 실제 active로 전환된 사용자 ID 목록
     */
    @SuppressWarnings("unchecked")
    public List<String> admitOldest(String eventId, int limit, int activeTtlSeconds, Instant now) {
        if (limit <= 0) {
            return List.of();
        }

        // waiting ZSET에서 오래 기다린 사용자부터 꺼내 active TTL key를 발급한다.
        // pop과 active 기록을 Lua 한 번으로 처리해야 중복 입장과 유실 사이의 빈틈이 생기지 않는다.
        List<String> result = redisTemplate.execute(
                admitScript,
                List.of(keys.waiting(eventId), keys.activePrefix(eventId), keys.activeUsers(eventId)),
                Integer.toString(limit),
                Integer.toString(activeTtlSeconds),
                Long.toString(now.toEpochMilli())
        );
        return result == null ? List.of() : result;
    }
}
