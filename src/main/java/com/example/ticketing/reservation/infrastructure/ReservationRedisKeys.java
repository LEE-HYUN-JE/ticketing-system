package com.example.ticketing.reservation.infrastructure;

import org.springframework.stereotype.Component;

@Component
public class ReservationRedisKeys {

    public String seat(String eventId, String seatId) {
        return "seat:%s:%s".formatted(eventId, seatId);
    }

    public String reservationUser(String eventId, String userId) {
        return "reservation:user:%s:%s".formatted(eventId, userId);
    }

    public String idempotency(String eventId, String userId, String idempotencyKey) {
        return "idempotency:%s:%s:%s".formatted(eventId, userId, idempotencyKey);
    }
}
