package com.example.ticketing.reservation.application;

import com.example.ticketing.reservation.api.dto.ReservationResponse;
import com.example.ticketing.reservation.domain.ReservationEvent;
import com.example.ticketing.reservation.domain.SeatClaimResult;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.infrastructure.RedisReservationRepository;
import com.example.ticketing.reservation.persistence.ReservationEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 좌석 예매 요청의 애플리케이션 유스케이스를 조율한다.
 *
 * <p>이 서비스는 입력값 검증, 좌석 ID 정책 검증, Redis 원자 선점 호출, 성공 이벤트 발행을 담당한다.
 * 좌석 중복과 사용자 중복 같은 동시성 판단은 Java 락이 아니라 Redis Lua script에서 수행한다.</p>
 */
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

    /**
     * active admission을 가진 사용자에게 좌석 선점을 시도한다.
     * Redis Lua script가 active 여부, idempotency replay, 사용자 중복 예매, 좌석 중복 점유를 한 번에 판단하며,
     * 성공한 경우에만 Redis Stream에 MySQL 비동기 저장 이벤트를 발행한다.
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @param seatId 사용자가 선점하려는 좌석 식별자
     * @param idempotencyKey 클라이언트 재시도 중복을 식별하는 멱등성 키
     * @return 좌석 선점 성공 또는 실패 상태를 담은 API 응답
     * @throws IllegalArgumentException 필수 값이 비어 있거나 멱등성 키가 너무 긴 경우
     */
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
