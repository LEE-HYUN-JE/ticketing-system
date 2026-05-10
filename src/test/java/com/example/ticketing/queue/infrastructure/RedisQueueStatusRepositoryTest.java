package com.example.ticketing.queue.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisQueueStatusRepositoryTest extends RedisIntegrationTestSupport {

    @Autowired
    private RedisQueueRepository repository;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void findsTokenMapping() {
        String token = UUID.randomUUID().toString();
        Instant createdAt = Instant.parse("2026-05-10T00:00:00Z");

        repository.saveTokenMapping(token, "holiday-2026", "user-1", createdAt, Duration.ofHours(1));

        assertThat(repository.findTokenMapping(token))
                .hasValueSatisfying(mapping -> {
                    assertThat(mapping.eventId()).isEqualTo("holiday-2026");
                    assertThat(mapping.userId()).isEqualTo("user-1");
                    assertThat(mapping.createdAt()).isEqualTo(createdAt);
                });
    }

    @Test
    void readsActiveTtl() {
        redisTemplate.opsForValue().set(keys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        assertThat(repository.getActiveTtlSeconds("holiday-2026", "user-1")).isBetween(1L, 60L);
    }

    @Test
    void returnsEmptyForMissingToken() {
        assertThat(repository.findTokenMapping(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void returnsNegativeTtlForExpiredActiveAdmission() {
        assertThat(repository.getActiveTtlSeconds("holiday-2026", "user-1")).isLessThan(0);
    }
}

