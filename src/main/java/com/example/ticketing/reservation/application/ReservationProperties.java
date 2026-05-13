package com.example.ticketing.reservation.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 좌석 예매 기능의 운영 파라미터를 외부 설정에서 바인딩한다.
 *
 * @param seatCapacity 이벤트에서 예약 가능한 총 좌석 수
 * @param seatIdPrefix 유효한 좌석 ID가 가져야 하는 접두사
 * @param idempotencyTtlSeconds Redis idempotency cache 유지 시간
 * @param idempotencyKeyMaxLength 클라이언트가 보낼 수 있는 {@code Idempotency-Key} 최대 길이
 */
@Validated
@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(
        @Min(1) int seatCapacity,
        @NotBlank String seatIdPrefix,
        @Min(1) int idempotencyTtlSeconds,
        @Min(1) int idempotencyKeyMaxLength
) {
}
