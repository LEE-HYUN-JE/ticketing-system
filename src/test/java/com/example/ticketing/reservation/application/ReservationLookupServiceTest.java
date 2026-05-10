package com.example.ticketing.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationLookupServiceTest extends RedisIntegrationTestSupport {

    @Autowired
    private SeatReservationService reservationService;

    @Autowired
    private ReservationLookupService lookupService;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Test
    void returnsNotReservedBeforeClaim() {
        var response = lookupService.getReservation("holiday-2026", "user-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.NOT_RESERVED);
        assertThat(response.seatId()).isNull();
    }

    @Test
    void returnsReservedAfterClaim() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));
        reservationService.claimSeat("holiday-2026", "user-1", "seat-10", "reserve-1");

        var response = lookupService.getReservation("holiday-2026", "user-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.seatId()).isEqualTo("seat-10");
    }
}
