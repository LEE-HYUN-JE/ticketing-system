package com.example.ticketing.reservation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 최종 예약 row를 MySQL에 저장하는 Spring Data JPA repository.
 *
 * <p>명시적인 조회 메서드는 두지 않는다. 현재 persistence worker는 Redis Stream 이벤트를
 * {@link ReservationEntity}로 저장하는 쓰기 경계만 필요하다.</p>
 */
public interface ReservationJpaRepository extends JpaRepository<ReservationEntity, String> {
}
