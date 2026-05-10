package com.example.ticketing.reservation.api;

import com.example.ticketing.reservation.api.ReservationDtos.ReservationRequest;
import com.example.ticketing.reservation.api.ReservationDtos.ReservationResponse;
import com.example.ticketing.reservation.application.ReservationLookupService;
import com.example.ticketing.reservation.application.SeatReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/events/{eventId}/reservations")
public class ReservationController {

    private final SeatReservationService seatReservationService;
    private final ReservationLookupService reservationLookupService;

    public ReservationController(
            SeatReservationService seatReservationService,
            ReservationLookupService reservationLookupService
    ) {
        this.seatReservationService = seatReservationService;
        this.reservationLookupService = reservationLookupService;
    }

    @PostMapping
    public ReservationResponse claimSeat(
            @PathVariable @NotBlank(message = "eventId is required") String eventId,
            @Valid @RequestBody ReservationRequest request
    ) {
        return seatReservationService.claimSeat(eventId, request.userId(), request.seatId());
    }

    @GetMapping("/users/{userId}")
    public ReservationResponse getReservation(
            @PathVariable @NotBlank(message = "eventId is required") String eventId,
            @PathVariable @NotBlank(message = "userId is required") String userId
    ) {
        return reservationLookupService.getReservation(eventId, userId);
    }
}

