package com.example.ticketing.reservation.application;

import com.example.ticketing.reservation.api.dto.ReservationResponse;
import com.example.ticketing.reservation.domain.ReservationModels.ReservationEvent;
import com.example.ticketing.reservation.domain.ReservationModels.SeatClaimResult;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.infrastructure.RedisReservationRepository;
import com.example.ticketing.reservation.persistence.ReservationEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SeatReservationService {

    private final RedisReservationRepository reservationRepository;
    private final SeatIdValidator seatIdValidator;
    private final ReservationProperties properties;
    private final ReservationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public SeatReservationService(
            RedisReservationRepository reservationRepository,
            SeatIdValidator seatIdValidator,
            ReservationProperties properties,
            ReservationEventPublisher eventPublisher
    ) {
        this(reservationRepository, seatIdValidator, properties, eventPublisher, Clock.systemUTC());
    }

    SeatReservationService(
            RedisReservationRepository reservationRepository,
            SeatIdValidator seatIdValidator,
            ReservationProperties properties,
            ReservationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.seatIdValidator = seatIdValidator;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
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

        Instant now = Instant.now(clock);
        SeatClaimResult result = reservationRepository.claimSeat(
                eventId,
                userId,
                seatId,
                idempotencyKey,
                now,
                properties.idempotencyTtlSeconds()
        );
        if (result.status() == ReservationStatus.RESERVED) {
            // API 응답은 Redis 선점 성공을 기준으로 즉시 반환한다.
            // MySQL 저장은 Redis Stream worker가 나중에 처리하므로 hot path가 DB connection에 묶이지 않는다.
            eventPublisher.publish(new ReservationEvent(
                    UUID.randomUUID().toString(),
                    eventId, userId, seatId,
                    result.status().name(),
                    now, idempotencyKey
            ));
        }
        return new ReservationResponse(result.status(), result.seatId(), result.message());
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
