package com.example.ticketing.reservation.domain;

import java.time.Instant;

/**
 * 사용자별 예매 상태 조회 결과다.
 *
 * <p>현재 조회 API는 MySQL 영속화 완료 여부가 아니라 Redis에 기록된 실시간 선점 상태를 기준으로 응답한다.
 * 따라서 사용자는 worker 저장 지연과 무관하게 자신이 선점한 좌석을 확인할 수 있다.</p>
 *
 * @param status 예약 상태
 * @param seatId 예약된 좌석 식별자
 * @param reservedAt 좌석 선점 시각
 */
public record ReservationLookupResult(ReservationStatus status, String seatId, Instant reservedAt) {
}
