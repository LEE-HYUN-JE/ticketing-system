package com.example.ticketing.reservation.domain;

public record SeatClaimResult(ReservationStatus status, String seatId, String message) {
}
