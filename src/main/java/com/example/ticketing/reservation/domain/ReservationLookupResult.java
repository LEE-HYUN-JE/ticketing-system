package com.example.ticketing.reservation.domain;

import java.time.Instant;

public record ReservationLookupResult(ReservationStatus status, String seatId, Instant reservedAt) {
}
