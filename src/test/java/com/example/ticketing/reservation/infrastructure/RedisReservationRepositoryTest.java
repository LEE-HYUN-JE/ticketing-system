package com.example.ticketing.reservation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisReservationRepositoryTest extends RedisIntegrationTestSupport {

    @Autowired
    private RedisReservationRepository repository;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Autowired
    private ReservationRedisKeys reservationKeys;

    @Test
    void activeUserCanReserveSeat() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var result = repository.claimSeat("holiday-2026", "user-1", "seat-10", Instant.parse("2026-05-10T00:00:00Z"));

        assertThat(result.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(result.seatId()).isEqualTo("seat-10");
        assertThat(redisTemplate.opsForValue().get(reservationKeys.seat("holiday-2026", "seat-10"))).isEqualTo("user-1");
        assertThat(repository.findUserReservation("holiday-2026", "user-1"))
                .hasValueSatisfying(lookup -> {
                    assertThat(lookup.status()).isEqualTo(ReservationStatus.RESERVED);
                    assertThat(lookup.seatId()).isEqualTo("seat-10");
                });
    }

    @Test
    void userWithoutActiveAdmissionCannotReserveSeat() {
        var result = repository.claimSeat("holiday-2026", "user-1", "seat-10", Instant.now());

        assertThat(result.status()).isEqualTo(ReservationStatus.NOT_ACTIVE);
        assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-10"))).isFalse();
        assertThat(repository.findUserReservation("holiday-2026", "user-1")).isEmpty();
    }
}

