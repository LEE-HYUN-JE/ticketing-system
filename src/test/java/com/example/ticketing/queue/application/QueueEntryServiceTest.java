package com.example.ticketing.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.api.dto.QueueEntryResponse;
import com.example.ticketing.queue.domain.QueueStatus;
import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class QueueEntryServiceTest extends RedisIntegrationTestSupport {

    @Autowired
    private QueueEntryService service;

    @Autowired
    private RedisQueueRepository repository;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void issuesTokenWithoutCalculatingWaitingPosition() {
        QueueEntryResponse response = service.enter("holiday-2026", "user-1");

        assertThat(response.queueToken()).isNotBlank();
        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isNull();
        assertThat(response.totalWaiting()).isNull();
        assertThat(response.pollAfterSeconds()).isEqualTo(5);
        assertThat(repository.findExistingToken("holiday-2026", "user-1")).contains(response.queueToken());
    }

    @Test
    void reusesExistingWaitingStateForDuplicateEntry() {
        QueueEntryResponse first = service.enter("holiday-2026", "user-1");
        QueueEntryResponse second = service.enter("holiday-2026", "user-1");

        assertThat(second.queueToken()).isEqualTo(first.queueToken());
        assertThat(second.rank()).isNull();
        assertThat(second.totalWaiting()).isNull();
        assertThat(repository.getWaitingPosition("holiday-2026", "user-1").orElseThrow().totalWaiting())
                .isEqualTo(1);
    }

    @Test
    void defersRankCalculationToQueueStatusLookup() {
        QueueEntryResponse first = service.enter("holiday-2026", "user-1");
        QueueEntryResponse second = service.enter("holiday-2026", "user-2");

        assertThat(first.rank()).isNull();
        assertThat(second.rank()).isNull();
        assertThat(repository.getWaitingPosition("holiday-2026", "user-1").orElseThrow().rank())
                .isEqualTo(1);
        assertThat(repository.getWaitingPosition("holiday-2026", "user-2").orElseThrow().rank())
                .isEqualTo(2);
    }

    @Test
    void concurrentDuplicateEntriesReuseSingleTokenAndSingleWaitingMember() throws Exception {
        int requestCount = 100;
        List<Callable<String>> requests = java.util.stream.IntStream.range(0, requestCount)
                .mapToObj(index -> (Callable<String>) () -> service.enter("holiday-2026", "user-1").queueToken())
                .toList();

        try (var executor = Executors.newFixedThreadPool(16)) {
            List<String> tokens = executor.invokeAll(requests).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();

            assertThat(new HashSet<>(tokens)).hasSize(1);
        }

        assertThat(redisTemplate.opsForZSet().size(keys.waiting("holiday-2026"))).isEqualTo(1);
        assertThat(repository.findExistingToken("holiday-2026", "user-1")).isPresent();
    }
}
