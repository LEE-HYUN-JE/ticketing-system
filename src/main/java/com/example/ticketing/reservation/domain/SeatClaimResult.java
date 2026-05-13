package com.example.ticketing.reservation.domain;

/**
 * Redis Lua script가 좌석 선점 시도 후 반환한 결과를 애플리케이션 계층으로 옮기는 값이다.
 *
 * <p>{@code status}는 성공/실패의 비즈니스 의미를, {@code seatId}는 성공 또는 기존 예약 좌석을,
 * {@code message}는 API 응답에 포함할 짧은 설명을 담는다.</p>
 *
 * @param status 좌석 선점 결과 상태
 * @param seatId 결과와 관련된 좌석 식별자
 * @param message 클라이언트에 전달할 결과 설명
 */
public record SeatClaimResult(ReservationStatus status, String seatId, String message) {
}
