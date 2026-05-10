package com.example.ticketing.reservation.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(
        @Min(1) int seatCapacity,
        @NotBlank String seatIdPrefix
) {
}

