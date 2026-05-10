package com.example.ticketing.reservation.application;

import java.util.OptionalInt;
import org.springframework.stereotype.Component;

@Component
public class SeatIdValidator {

    private final ReservationProperties properties;

    public SeatIdValidator(ReservationProperties properties) {
        this.properties = properties;
    }

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

