package com.example.ticketing.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SeatReservationServiceTest extends RedisIntegrationTestSupport {

    @Autowired
    private SeatReservationService service;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Test
    void activeUserGetsReservedResponse() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var response = service.claimSeat("holiday-2026", "user-1", "seat-10");

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.seatId()).isEqualTo("seat-10");
    }

    @Test
    void inactiveUserGetsNotActiveResponse() {
        var response = service.claimSeat("holiday-2026", "user-1", "seat-10");

        assertThat(response.status()).isEqualTo(ReservationStatus.NOT_ACTIVE);
        assertThat(response.seatId()).isNull();
    }

    @Test
    void invalidSeatGetsInvalidSeatResponse() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var response = service.claimSeat("holiday-2026", "user-1", "seat-9999");

        assertThat(response.status()).isEqualTo(ReservationStatus.INVALID_SEAT);
        assertThat(response.seatId()).isNull();
    }
}

