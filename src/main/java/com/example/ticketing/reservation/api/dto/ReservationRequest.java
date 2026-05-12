package com.example.ticketing.reservation.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReservationRequest(
        @NotBlank(message = "userId is required")
        String userId,
        @NotBlank(message = "seatId is required")
        String seatId
) {
}
