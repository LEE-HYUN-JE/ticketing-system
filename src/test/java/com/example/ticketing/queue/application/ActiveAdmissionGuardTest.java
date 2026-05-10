package com.example.ticketing.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActiveAdmissionGuardTest extends RedisIntegrationTestSupport {

    @Autowired
    private ActiveAdmissionGuard guard;

    @Autowired
    private QueueMetricsService metricsService;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void allowsUserWithActiveAdmission() {
        redisTemplate.opsForValue().set(keys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        assertThat(guard.hasActiveAdmission("holiday-2026", "user-1")).isTrue();
        guard.requireActiveAdmission("holiday-2026", "user-1");
    }

    @Test
    void rejectsMissingActiveAdmissionAndCountsExpiredLookup() {
        assertThat(guard.hasActiveAdmission("holiday-2026", "user-1")).isFalse();

        assertThatThrownBy(() -> guard.requireActiveAdmission("holiday-2026", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active admission");
        assertThat(metricsService.snapshot().expiredLookupCount()).isEqualTo(1);
    }

    @Test
    void rejectsExpiredActiveAdmission() throws Exception {
        redisTemplate.opsForValue().set(keys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofMillis(100));
        Thread.sleep(150);

        assertThat(guard.hasActiveAdmission("holiday-2026", "user-1")).isFalse();
    }
}

