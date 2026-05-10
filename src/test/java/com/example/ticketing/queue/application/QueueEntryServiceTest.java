package com.example.ticketing.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.api.QueueEntryDtos.QueueEntryResponse;
import com.example.ticketing.queue.domain.QueueStatus;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class QueueEntryServiceTest extends RedisIntegrationTestSupport {

    @Autowired
    private QueueEntryService service;

    @Autowired
    private RedisQueueRepository repository;

    @Test
    void issuesTokenAndReturnsWaitingPosition() {
        QueueEntryResponse response = service.enter("holiday-2026", "user-1");

        assertThat(response.queueToken()).isNotBlank();
        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(1);
        assertThat(response.totalWaiting()).isEqualTo(1);
        assertThat(response.pollAfterSeconds()).isEqualTo(5);
        assertThat(repository.findExistingToken("holiday-2026", "user-1")).contains(response.queueToken());
    }

    @Test
    void reusesExistingWaitingStateForDuplicateEntry() {
        QueueEntryResponse first = service.enter("holiday-2026", "user-1");
        QueueEntryResponse second = service.enter("holiday-2026", "user-1");

        assertThat(second.queueToken()).isEqualTo(first.queueToken());
        assertThat(second.rank()).isEqualTo(1);
        assertThat(second.totalWaiting()).isEqualTo(1);
    }

    @Test
    void calculatesRanksForMultipleUsers() {
        QueueEntryResponse first = service.enter("holiday-2026", "user-1");
        QueueEntryResponse second = service.enter("holiday-2026", "user-2");

        assertThat(first.rank()).isEqualTo(1);
        assertThat(second.rank()).isEqualTo(2);
        assertThat(second.totalWaiting()).isEqualTo(2);
    }
}

