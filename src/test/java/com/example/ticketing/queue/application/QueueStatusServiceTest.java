package com.example.ticketing.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.queue.api.QueueEntryDtos.QueueEntryResponse;
import com.example.ticketing.queue.api.QueueStatusDtos.QueueStatusResponse;
import com.example.ticketing.queue.domain.QueueStatus;
import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class QueueStatusServiceTest extends RedisIntegrationTestSupport {

    @Autowired
    private QueueEntryService queueEntryService;

    @Autowired
    private QueueStatusService queueStatusService;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void returnsWaitingStatusWithRankFields() {
        QueueEntryResponse entry = queueEntryService.enter("holiday-2026", "user-1");

        QueueStatusResponse status = queueStatusService.getStatus("holiday-2026", entry.queueToken());

        assertThat(status.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(status.rank()).isEqualTo(1);
        assertThat(status.totalWaiting()).isEqualTo(1);
        assertThat(status.pollAfterSeconds()).isEqualTo(5);
        assertThat(status.activeExpiresInSeconds()).isNull();
    }

    @Test
    void returnsEnteredStatusWithActiveTtl() {
        QueueEntryResponse entry = queueEntryService.enter("holiday-2026", "user-1");
        redisTemplate.opsForZSet().remove(keys.waiting("holiday-2026"), "user-1");
        redisTemplate.opsForValue().set(keys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        QueueStatusResponse status = queueStatusService.getStatus("holiday-2026", entry.queueToken());

        assertThat(status.status()).isEqualTo(QueueStatus.ENTERED);
        assertThat(status.rank()).isNull();
        assertThat(status.totalWaiting()).isNull();
        assertThat(status.pollAfterSeconds()).isNull();
        assertThat(status.activeExpiresInSeconds()).isBetween(1L, 60L);
    }

    @Test
    void returnsExpiredForUnknownToken() {
        QueueStatusResponse status = queueStatusService.getStatus("holiday-2026", UUID.randomUUID().toString());

        assertThat(status.status()).isEqualTo(QueueStatus.EXPIRED);
        assertThat(status.rank()).isNull();
        assertThat(status.totalWaiting()).isNull();
        assertThat(status.pollAfterSeconds()).isNull();
        assertThat(status.activeExpiresInSeconds()).isNull();
    }

    @Test
    void returnsExpiredWhenTokenHasNoWaitingOrActiveState() {
        QueueEntryResponse entry = queueEntryService.enter("holiday-2026", "user-1");
        redisTemplate.opsForZSet().remove(keys.waiting("holiday-2026"), "user-1");

        QueueStatusResponse status = queueStatusService.getStatus("holiday-2026", entry.queueToken());

        assertThat(status.status()).isEqualTo(QueueStatus.EXPIRED);
    }

    @Test
    void rejectsEventMismatch() {
        QueueEntryResponse entry = queueEntryService.enter("holiday-2026", "user-1");

        assertThatThrownBy(() -> queueStatusService.getStatus("other-event", entry.queueToken()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }
}

