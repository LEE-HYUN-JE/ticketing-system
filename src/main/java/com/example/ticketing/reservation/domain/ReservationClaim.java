package com.example.ticketing.reservation.domain;

import java.time.Instant;

/**
 * Redis에서 좌석 선점이 성공했음을 나타내는 최소 도메인 값이다.
 *
 * <p>이 값은 "누가, 어떤 이벤트에서, 어떤 좌석을, 언제 선점했는가"만 담는다.
 * 멱등성 키나 최종 저장 row 식별자처럼 persistence 경계에 가까운 정보는 별도 이벤트 타입에서 다룬다.</p>
 *
 * @param eventId 티켓팅 이벤트 식별자
 * @param userId 사용자 식별자
 * @param seatId 선점된 좌석 식별자
 * @param reservedAt 좌석이 선점된 시각
 */
public record ReservationClaim(String eventId, String userId, String seatId, Instant reservedAt) {
}
