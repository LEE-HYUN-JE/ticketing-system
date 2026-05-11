package com.example.ticketing.reservation.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class ReservationEntityConstraintTest extends RedisIntegrationTestSupport {

    @Autowired
    private ReservationJpaRepository jpaRepository;

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
    }

    @Test
    void duplicateEventUserThrowsConstraintViolation() {
        jpaRepository.save(entity("res-1", "event-1", "user-1", "seat-1", "idem-1"));

        assertThatThrownBy(() -> jpaRepository.saveAndFlush(
                entity(UUID.randomUUID().toString(), "event-1", "user-1", "seat-2", "idem-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateEventSeatThrowsConstraintViolation() {
        jpaRepository.save(entity("res-2", "event-1", "user-2", "seat-10", "idem-3"));

        assertThatThrownBy(() -> jpaRepository.saveAndFlush(
                entity(UUID.randomUUID().toString(), "event-1", "user-3", "seat-10", "idem-4")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentEventAllowsSameUserAndSeat() {
        jpaRepository.saveAndFlush(entity("res-3", "event-1", "user-1", "seat-1", "idem-5"));
        jpaRepository.saveAndFlush(entity("res-4", "event-2", "user-1", "seat-1", "idem-6"));

        // 다른 이벤트에서는 동일 사용자/좌석 저장 가능
        org.assertj.core.api.Assertions.assertThat(jpaRepository.count()).isEqualTo(2);
    }

    private ReservationEntity entity(String id, String eventId, String userId, String seatId, String idemKey) {
        return new ReservationEntity(id, eventId, userId, seatId, "RESERVED",
                Instant.parse("2026-05-10T06:00:00Z"), idemKey, Instant.now());
    }
}
