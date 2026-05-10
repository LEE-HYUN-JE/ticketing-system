package com.example.ticketing.queue.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisAdmissionRepositoryTest extends RedisIntegrationTestSupport {

    @Autowired
    private RedisAdmissionRepository admissionRepository;

    @Autowired
    private RedisQueueRepository queueRepository;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void atomicallyMovesOldestWaitingUsersToActiveKeys() {
        queueRepository.addWaitingUserIfAbsent("holiday-2026", "user-1", 100.0d);
        queueRepository.addWaitingUserIfAbsent("holiday-2026", "user-2", 200.0d);
        queueRepository.addWaitingUserIfAbsent("holiday-2026", "user-3", 300.0d);

        List<String> admitted = admissionRepository.admitOldest("holiday-2026", 2, 60, Instant.now());

        assertThat(admitted).containsExactly("user-1", "user-2");
        assertThat(redisTemplate.opsForZSet().size(keys.waiting("holiday-2026"))).isEqualTo(1);
        assertThat(redisTemplate.hasKey(keys.active("holiday-2026", "user-1"))).isTrue();
        assertThat(redisTemplate.hasKey(keys.active("holiday-2026", "user-2"))).isTrue();
        assertThat(redisTemplate.opsForZSet().score(keys.activeUsers("holiday-2026"), "user-1")).isNotNull();
    }

    @Test
    void activeAdmissionExpiresAfterTtl() throws Exception {
        queueRepository.addWaitingUserIfAbsent("holiday-2026", "user-1", 100.0d);

        List<String> admitted = admissionRepository.admitOldest("holiday-2026", 1, 1, Instant.now());

        assertThat(admitted).containsExactly("user-1");
        assertThat(queueRepository.getActiveTtlSeconds("holiday-2026", "user-1")).isBetween(0L, 1L);

        Thread.sleep(1200);

        assertThat(queueRepository.getActiveTtlSeconds("holiday-2026", "user-1")).isLessThan(0);
    }
}

