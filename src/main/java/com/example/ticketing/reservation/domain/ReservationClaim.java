package com.example.ticketing.reservation.domain;

import java.time.Instant;

public record ReservationClaim(String eventId, String userId, String seatId, Instant reservedAt) {
}
