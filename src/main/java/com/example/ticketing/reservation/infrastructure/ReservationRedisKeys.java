package com.example.ticketing.reservation.infrastructure;

import org.springframework.stereotype.Component;

/**
 * Reservation 도메인에서 사용하는 Redis key 이름을 생성한다.
 *
 * <p>좌석 선점, 사용자별 예약 상태, idempotency cache는 모두 Redis Lua script의 key 계약에 포함된다.
 * key naming을 이 클래스에 모아 script 호출부와 문서가 같은 용어를 사용하게 한다.</p>
 */
@Component
public class ReservationRedisKeys {

    /**
     * 좌석 점유 상태를 저장하는 String key.
     * 값은 해당 좌석을 선점한 userId다.
     */
    public String seat(String eventId, String seatId) {
        return "seat:%s:%s".formatted(eventId, seatId);
    }

    /**
     * 사용자별 예약 결과를 저장하는 Hash key.
     * 같은 event/user가 여러 좌석을 선점하지 못하게 하는 기준이기도 하다.
     */
    public String reservationUser(String eventId, String userId) {
        return "reservation:user:%s:%s".formatted(eventId, userId);
    }

    /**
     * 같은 예매 요청 재시도에 최초 결과를 replay하기 위한 Hash key.
     */
    public String idempotency(String eventId, String userId, String idempotencyKey) {
        return "idempotency:%s:%s:%s".formatted(eventId, userId, idempotencyKey);
    }
}
