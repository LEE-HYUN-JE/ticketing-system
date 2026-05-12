package com.example.ticketing.reservation.api.dto;

import com.example.ticketing.reservation.domain.ReservationStatus;

public record ReservationResponse(
        ReservationStatus status,
        String seatId,
        String message
) {
}
