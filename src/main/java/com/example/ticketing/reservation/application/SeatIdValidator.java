package com.example.ticketing.reservation.application;

import java.util.OptionalInt;
import org.springframework.stereotype.Component;

/**
 * 예약 요청의 좌석 ID가 현재 이벤트의 좌석 정책에 맞는지 검증한다.
 *
 * <p>현재 토이 도메인은 {@code seat-1}부터 {@code seat-{capacity}}까지를 유효 좌석으로 본다.
 * 잘못된 좌석 ID는 Redis Lua script까지 보내지 않고 애플리케이션 계층에서 빠르게 거절한다.</p>
 */
@Component
public class SeatIdValidator {

    private final ReservationProperties properties;

    public SeatIdValidator(ReservationProperties properties) {
        this.properties = properties;
    }

    /**
     * 좌석 ID가 설정된 prefix와 좌석 수 범위 안에 들어오는지 확인한다.
     *
     * @param seatId 클라이언트가 요청한 좌석 ID
     * @return 예약 가능한 좌석 ID 형식이면 {@code true}
     */
    public boolean isValid(String seatId) {
        return seatNumber(seatId)
                .stream()
                .anyMatch(number -> number >= 1 && number <= properties.seatCapacity());
    }

    private OptionalInt seatNumber(String seatId) {
        if (seatId == null || !seatId.startsWith(properties.seatIdPrefix())) {
            return OptionalInt.empty();
        }
        String numberPart = seatId.substring(properties.seatIdPrefix().length());
        if (numberPart.isBlank() || !numberPart.chars().allMatch(Character::isDigit)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(numberPart));
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }
}
