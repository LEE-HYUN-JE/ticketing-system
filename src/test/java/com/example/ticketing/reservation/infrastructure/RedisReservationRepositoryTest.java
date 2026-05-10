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

        var result = repository.claimSeat(
                "holiday-2026",
                "user-1",
                "seat-10",
                "reserve-1",
                Instant.parse("2026-05-10T00:00:00Z"),
                600
        );

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
        var result = repository.claimSeat("holiday-2026", "user-1", "seat-10", "reserve-1", Instant.now(), 600);

        assertThat(result.status()).isEqualTo(ReservationStatus.NOT_ACTIVE);
        assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-10"))).isFalse();
        assertThat(repository.findUserReservation("holiday-2026", "user-1")).isEmpty();
    }

    @Test
    void sameKeySameSeatReplaysReservedResultAndKeepsTtl() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var first = repository.claimSeat("holiday-2026", "user-1", "seat-10", "same-key", Instant.parse("2026-05-10T00:00:00Z"), 600);
        var second = repository.claimSeat("holiday-2026", "user-1", "seat-10", "same-key", Instant.parse("2026-05-10T00:00:01Z"), 600);

        assertThat(second).isEqualTo(first);
        String idempotencyKey = reservationKeys.idempotency("holiday-2026", "user-1", "same-key");
        assertThat(redisTemplate.opsForHash().get(idempotencyKey, "status")).isEqualTo("RESERVED");
        assertThat(redisTemplate.opsForHash().get(idempotencyKey, "seatId")).isEqualTo("seat-10");
        assertThat(redisTemplate.getExpire(idempotencyKey)).isPositive();
    }

    @Test
    void sameKeyDifferentSeatReplaysFirstResult() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var first = repository.claimSeat("holiday-2026", "user-1", "seat-10", "same-key", Instant.now(), 600);
        var second = repository.claimSeat("holiday-2026", "user-1", "seat-11", "same-key", Instant.now(), 600);

        assertThat(first.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(second).isEqualTo(first);
        assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-11"))).isFalse();
    }

    @Test
    void notActiveResultIsReplayedForSameKey() {
        var first = repository.claimSeat("holiday-2026", "user-1", "seat-10", "same-key", Instant.now(), 600);
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));
        var second = repository.claimSeat("holiday-2026", "user-1", "seat-10", "same-key", Instant.now(), 600);

        assertThat(first.status()).isEqualTo(ReservationStatus.NOT_ACTIVE);
        assertThat(second).isEqualTo(first);
        assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-10"))).isFalse();
    }

    @Test
    void seatAlreadyTakenResultIsReplayedForSameKey() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-2"), Instant.now().toString(), Duration.ofSeconds(60));
        repository.claimSeat("holiday-2026", "user-1", "seat-10", "owner-key", Instant.now(), 600);

        var first = repository.claimSeat("holiday-2026", "user-2", "seat-10", "taken-key", Instant.now(), 600);
        var second = repository.claimSeat("holiday-2026", "user-2", "seat-11", "taken-key", Instant.now(), 600);

        assertThat(first.status()).isEqualTo(ReservationStatus.SEAT_ALREADY_TAKEN);
        assertThat(first.seatId()).isEqualTo("seat-10");
        assertThat(second).isEqualTo(first);
        assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-11"))).isFalse();
    }

    @Test
    void differentKeyAfterReservationReturnsAlreadyReservedAndReplaysIt() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var first = repository.claimSeat("holiday-2026", "user-1", "seat-10", "key-a", Instant.now(), 600);
        var second = repository.claimSeat("holiday-2026", "user-1", "seat-11", "key-b", Instant.now(), 600);
        var replay = repository.claimSeat("holiday-2026", "user-1", "seat-12", "key-b", Instant.now(), 600);

        assertThat(first.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(second.status()).isEqualTo(ReservationStatus.ALREADY_RESERVED);
        assertThat(second.seatId()).isEqualTo("seat-10");
        assertThat(replay).isEqualTo(second);
        assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-11"))).isFalse();
        assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-12"))).isFalse();
    }

    @Test
    void sameIdempotencyKeyIsScopedByEventAndUser() {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-2"), Instant.now().toString(), Duration.ofSeconds(60));
        redisTemplate.opsForValue().set(queueKeys.active("concert-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        var first = repository.claimSeat("holiday-2026", "user-1", "seat-10", "same-key", Instant.now(), 600);
        var otherUser = repository.claimSeat("holiday-2026", "user-2", "seat-11", "same-key", Instant.now(), 600);
        var otherEvent = repository.claimSeat("concert-2026", "user-1", "seat-12", "same-key", Instant.now(), 600);

        assertThat(first.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(otherUser.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(otherUser.seatId()).isEqualTo("seat-11");
        assertThat(otherEvent.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(otherEvent.seatId()).isEqualTo("seat-12");
    }
}
