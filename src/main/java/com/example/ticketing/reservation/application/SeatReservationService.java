package com.example.ticketing.reservation.application;

import com.example.ticketing.reservation.api.ReservationDtos.ReservationResponse;
import com.example.ticketing.reservation.domain.ReservationModels.SeatClaimResult;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.infrastructure.RedisReservationRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SeatReservationService {

    private final RedisReservationRepository reservationRepository;
    private final SeatIdValidator seatIdValidator;
    private final Clock clock;

    @Autowired
    public SeatReservationService(
            RedisReservationRepository reservationRepository,
            SeatIdValidator seatIdValidator
    ) {
        this(reservationRepository, seatIdValidator, Clock.systemUTC());
    }

    SeatReservationService(
            RedisReservationRepository reservationRepository,
            SeatIdValidator seatIdValidator,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.seatIdValidator = seatIdValidator;
        this.clock = clock;
    }

    public ReservationResponse claimSeat(String eventId, String userId, String seatId) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);
        validateRequired("seatId", seatId);
        if (!seatIdValidator.isValid(seatId)) {
            return new ReservationResponse(ReservationStatus.INVALID_SEAT, null, "Invalid seat id");
        }

        SeatClaimResult result = reservationRepository.claimSeat(eventId, userId, seatId, Instant.now(clock));
        return new ReservationResponse(result.status(), result.seatId(), result.message());
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
