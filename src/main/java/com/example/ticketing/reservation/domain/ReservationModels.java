package com.example.ticketing.reservation.domain;

import java.time.Instant;

public final class ReservationModels {

    private ReservationModels() {
    }

    public record ReservationClaim(String eventId, String userId, String seatId, Instant reservedAt) {
    }

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

    public record SeatClaimResult(ReservationStatus status, String seatId, String message) {
    }

    public record ReservationLookupResult(ReservationStatus status, String seatId, Instant reservedAt) {
    }
}

