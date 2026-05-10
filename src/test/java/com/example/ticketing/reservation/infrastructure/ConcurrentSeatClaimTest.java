package com.example.ticketing.reservation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConcurrentSeatClaimTest extends RedisIntegrationTestSupport {

    @Autowired
    private RedisReservationRepository repository;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Test
    void sameSeatCanBeReservedOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ReservationStatus>> calls = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                String userId = "user-" + i;
                redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", userId), Instant.now().toString(), Duration.ofSeconds(60));
                calls.add(() -> repository.claimSeat("holiday-2026", userId, "seat-10", "key-" + userId, Instant.now(), 600).status());
            }

            List<ReservationStatus> statuses = executor.invokeAll(calls)
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .toList();

            assertThat(statuses).filteredOn(status -> status == ReservationStatus.RESERVED).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == ReservationStatus.SEAT_ALREADY_TAKEN).hasSize(7);
        } finally {
            executor.shutdownNow();
        }
    }
}
