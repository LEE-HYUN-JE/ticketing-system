package com.example.ticketing.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DuplicateReservationServiceTest extends RedisIntegrationTestSupport {

    @Autowired
    private SeatReservationService service;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Test
    void sameUserCannotReserveAnotherSeatInSameEvent() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var first = service.claimSeat("holiday-2026", "user-1", "seat-10", "key-a");
        var second = service.claimSeat("holiday-2026", "user-1", "seat-11", "key-b");

        assertThat(first.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(second.status()).isEqualTo(ReservationStatus.ALREADY_RESERVED);
        assertThat(second.seatId()).isEqualTo("seat-10");
    }
}
