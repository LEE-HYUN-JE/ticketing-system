package com.example.ticketing.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.api.QueueEntryDtos.QueueEntryResponse;
import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdmissionSchedulerServiceTest extends RedisIntegrationTestSupport {

    @Autowired
    private QueueEntryService queueEntryService;

    @Autowired
    private AdmissionSchedulerService schedulerService;

    @Autowired
    private QueueMetricsService metricsService;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void admitsNoMoreThanConfiguredRateInOneTick() {
        for (int i = 1; i <= 25; i++) {
            queueEntryService.enter("holiday-2026", "user-" + i);
        }

        List<String> admitted = schedulerService.admitOneTick("holiday-2026");

        assertThat(admitted).hasSize(20);
        assertThat(redisTemplate.opsForZSet().size(keys.waiting("holiday-2026"))).isEqualTo(5);
        assertThat(metricsService.snapshot().admittedCount()).isEqualTo(20);
    }

    @Test
    void admitsOldestUsersFirst() {
        queueEntryService.enter("holiday-2026", "user-1");
        queueEntryService.enter("holiday-2026", "user-2");
        queueEntryService.enter("holiday-2026", "user-3");

        List<String> admitted = schedulerService.admitOneTick("holiday-2026");

        assertThat(admitted).containsExactly("user-1", "user-2", "user-3");
    }

    @Test
    void admitsOnlyExistingUsersWhenQueueIsSmallerThanRate() {
        queueEntryService.enter("holiday-2026", "user-1");
        queueEntryService.enter("holiday-2026", "user-2");

        List<String> admitted = schedulerService.admitOneTick("holiday-2026");

        assertThat(admitted).containsExactly("user-1", "user-2");
        assertThat(redisTemplate.opsForZSet().size(keys.waiting("holiday-2026"))).isZero();
    }

    @Test
    void emptyQueueReturnsNoAdmissions() {
        List<String> admitted = schedulerService.admitOneTick("holiday-2026");

        assertThat(admitted).isEmpty();
    }

    @Test
    void admitsAllRegisteredEventsWithoutKeysCommand() {
        QueueEntryResponse first = queueEntryService.enter("holiday-2026", "user-1");
        QueueEntryResponse second = queueEntryService.enter("new-year-2027", "user-2");

        long admitted = schedulerService.admitAllRegisteredEvents();

        assertThat(admitted).isEqualTo(2);
        assertThat(redisTemplate.hasKey(keys.active("holiday-2026", "user-1"))).isTrue();
        assertThat(redisTemplate.hasKey(keys.active("new-year-2027", "user-2"))).isTrue();
        assertThat(first.queueToken()).isNotBlank();
        assertThat(second.queueToken()).isNotBlank();
    }
}

