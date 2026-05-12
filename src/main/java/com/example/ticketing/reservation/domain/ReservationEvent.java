package com.example.ticketing.reservation.domain;

import java.time.Instant;

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
