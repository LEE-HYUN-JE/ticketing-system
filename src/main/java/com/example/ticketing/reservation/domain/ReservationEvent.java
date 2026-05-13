package com.example.ticketing.reservation.domain;

import java.time.Instant;

/**
 * Redis에서 좌석 선점에 성공한 뒤 MySQL 비동기 저장 worker로 전달하는 이벤트다.
 *
 * <p>API 응답은 Redis 선점 결과를 기준으로 즉시 반환되고, 이 이벤트는 Redis Stream에 append된다.
 * worker는 이 값을 읽어 {@code reservations} 테이블에 최종 예약 row를 저장한다.</p>
 *
 * @param reservationId MySQL에 저장할 예약 식별자
 * @param eventId 티켓팅 이벤트 식별자
 * @param userId 사용자 식별자
 * @param seatId 선점된 좌석 식별자
 * @param status 예약 상태 문자열
 * @param reservedAt 좌석 선점 시각
 * @param idempotencyKey 최초 예매 요청에 사용된 멱등성 키
 */
public record ReservationEvent(
        String reservationId,
        String eventId,
        String userId,
        String seatId,
        String status,
        Instant reservedAt,
        String idempotencyKey
) {
}
