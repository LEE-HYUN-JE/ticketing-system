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
    private final ReservationProperties properties;
    private final Clock clock;

    @Autowired
    public SeatReservationService(
            RedisReservationRepository reservationRepository,
            SeatIdValidator seatIdValidator,
            ReservationProperties properties
    ) {
        this(reservationRepository, seatIdValidator, properties, Clock.systemUTC());
    }

    SeatReservationService(
            RedisReservationRepository reservationRepository,
            SeatIdValidator seatIdValidator,
            ReservationProperties properties,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.seatIdValidator = seatIdValidator;
        this.properties = properties;
        this.clock = clock;
    }

    public ReservationResponse claimSeat(String eventId, String userId, String seatId, String idempotencyKey) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);
        validateRequired("seatId", seatId);
        validateRequired("Idempotency-Key", idempotencyKey);
        if (idempotencyKey.length() > properties.idempotencyKeyMaxLength()) {
            throw new IllegalArgumentException("Idempotency-Key must be at most "
                    + properties.idempotencyKeyMaxLength() + " characters");
        }
        if (!seatIdValidator.isValid(seatId)) {
            return new ReservationResponse(ReservationStatus.INVALID_SEAT, null, "Invalid seat id");
        }

        SeatClaimResult result = reservationRepository.claimSeat(
                eventId,
                userId,
                seatId,
                idempotencyKey,
                Instant.now(clock),
                properties.idempotencyTtlSeconds()
        );
        return new ReservationResponse(result.status(), result.seatId(), result.message());
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
