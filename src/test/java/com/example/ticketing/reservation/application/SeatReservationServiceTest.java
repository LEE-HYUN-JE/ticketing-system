package com.example.ticketing.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        var response = service.claimSeat("holiday-2026", "user-1", "seat-10", "reserve-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.seatId()).isEqualTo("seat-10");
    }

    @Test
    void inactiveUserGetsNotActiveResponse() {
        var response = service.claimSeat("holiday-2026", "user-1", "seat-10", "reserve-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.NOT_ACTIVE);
        assertThat(response.seatId()).isNull();
    }

    @Test
    void invalidSeatGetsInvalidSeatResponse() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var response = service.claimSeat("holiday-2026", "user-1", "seat-9999", "reserve-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.INVALID_SEAT);
        assertThat(response.seatId()).isNull();
    }

    @Test
    void sameIdempotencyKeyReplaysOriginalResult() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var first = service.claimSeat("holiday-2026", "user-1", "seat-10", "retry-key");
        var second = service.claimSeat("holiday-2026", "user-1", "seat-11", "retry-key");

        assertThat(first.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void differentIdempotencyKeyReturnsAlreadyReservedAfterFirstReservation() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var first = service.claimSeat("holiday-2026", "user-1", "seat-10", "key-a");
        var second = service.claimSeat("holiday-2026", "user-1", "seat-11", "key-b");
        var replay = service.claimSeat("holiday-2026", "user-1", "seat-12", "key-b");

        assertThat(first.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(second.status()).isEqualTo(ReservationStatus.ALREADY_RESERVED);
        assertThat(second.seatId()).isEqualTo("seat-10");
        assertThat(replay).isEqualTo(second);
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> service.claimSeat("holiday-2026", "user-1", "seat-10", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency-Key is required");
    }

    @Test
    void rejectsOverlengthIdempotencyKey() {
        String key = "a".repeat(121);

        assertThatThrownBy(() -> service.claimSeat("holiday-2026", "user-1", "seat-10", key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency-Key must be at most 120 characters");
    }
}
