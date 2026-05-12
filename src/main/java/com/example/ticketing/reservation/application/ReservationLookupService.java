package com.example.ticketing.reservation.application;

import com.example.ticketing.reservation.api.dto.ReservationResponse;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.infrastructure.RedisReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReservationLookupService {

    private final RedisReservationRepository reservationRepository;

    public ReservationLookupService(RedisReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    public ReservationResponse getReservation(String eventId, String userId) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);
        return reservationRepository.findUserReservation(eventId, userId)
                .map(result -> new ReservationResponse(result.status(), result.seatId(), null))
                .orElseGet(() -> new ReservationResponse(ReservationStatus.NOT_RESERVED, null, null));
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}

