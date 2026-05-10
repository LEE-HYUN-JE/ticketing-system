package com.example.ticketing.reservation.api;

import com.example.ticketing.reservation.domain.ReservationStatus;
import jakarta.validation.constraints.NotBlank;

public final class ReservationDtos {

    private ReservationDtos() {
    }

    public record ReservationRequest(
            @NotBlank(message = "userId is required")
            String userId,
            @NotBlank(message = "seatId is required")
            String seatId
    ) {
    }

    public record ReservationResponse(
            ReservationStatus status,
            String seatId,
            String message
    ) {
    }
}

