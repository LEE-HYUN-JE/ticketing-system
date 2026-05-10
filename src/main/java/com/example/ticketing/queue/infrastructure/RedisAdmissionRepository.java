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

    @SuppressWarnings("unchecked")
    public List<String> admitOldest(String eventId, int limit, int activeTtlSeconds, Instant now) {
        if (limit <= 0) {
            return List.of();
        }

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
