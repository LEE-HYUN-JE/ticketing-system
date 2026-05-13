package com.example.ticketing.reservation.application;

import com.example.ticketing.reservation.api.dto.ReservationResponse;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.infrastructure.RedisReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 사용자별 예매 결과를 Redis 기준으로 조회하는 서비스다.
 *
 * <p>예매 API는 MySQL 저장을 기다리지 않고 Redis 선점 결과를 즉시 반환한다. 조회 역시 같은 기준을 사용해,
 * worker가 아직 MySQL에 반영하기 전이어도 사용자가 자신의 선점 결과를 확인할 수 있게 한다.</p>
 */
@Service
public class ReservationLookupService {

    private final RedisReservationRepository reservationRepository;

    public ReservationLookupService(RedisReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    /**
     * event/user 기준으로 Redis에 기록된 예매 결과를 조회한다.
     * 이 API는 MySQL 영속화 완료 여부와 무관하게 Redis의 실시간 선점 결과를 사용자에게 보여준다.
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @return Redis에 선점 결과가 있으면 해당 예약 정보, 없으면 {@code NOT_RESERVED}
     */
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
