package com.example.ticketing.queue.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.domain.QueueModels.QueuePosition;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisQueueRepositoryTest extends RedisIntegrationTestSupport {

    @Autowired
    private RedisQueueRepository repository;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void savesReverseIndexAndEventRegistryForDuplicateEntryLookup() {
        Instant now = Instant.parse("2026-05-10T00:00:00Z");

        repository.saveTokenMapping("token-1", "holiday-2026", "user-1", now, Duration.ofHours(1));
        repository.addWaitingUserIfAbsent("holiday-2026", "user-1", 1.0d);
        repository.addWaitingUserIfAbsent("holiday-2026", "user-1", 2.0d);

        assertThat(repository.findExistingToken("holiday-2026", "user-1")).contains("token-1");
        assertThat(redisTemplate.opsForSet().isMember(keys.queueEvents(), "holiday-2026")).isTrue();
        assertThat(redisTemplate.opsForZSet().size(keys.waiting("holiday-2026"))).isEqualTo(1);
        assertThat(repository.getWaitingPosition("holiday-2026", "user-1"))
                .contains(new QueuePosition(1, 1));
    }

    @Test
    void calculatesOldestFirstRankFromSortedSet() {
        repository.addWaitingUserIfAbsent("holiday-2026", "user-1", 100.0d);
        repository.addWaitingUserIfAbsent("holiday-2026", "user-2", 200.0d);
        repository.addWaitingUserIfAbsent("holiday-2026", "user-3", 300.0d);

        assertThat(repository.getWaitingPosition("holiday-2026", "user-1"))
                .contains(new QueuePosition(1, 3));
        assertThat(repository.getWaitingPosition("holiday-2026", "user-2"))
                .contains(new QueuePosition(2, 3));
        assertThat(repository.getWaitingPosition("holiday-2026", "user-3"))
                .contains(new QueuePosition(3, 3));
    }
}

