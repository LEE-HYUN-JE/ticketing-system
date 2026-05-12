package com.example.ticketing.queue.infrastructure;

import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Repository;

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
