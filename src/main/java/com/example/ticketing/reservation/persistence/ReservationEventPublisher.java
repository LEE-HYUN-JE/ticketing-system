package com.example.ticketing.reservation.persistence;

import com.example.ticketing.reservation.domain.ReservationEvent;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 좌석 선점 성공 이벤트를 Redis Stream에 발행하는 비동기 경계다.
 *
 * <p>Reservation API는 Redis에서 선점이 성공하면 사용자에게 즉시 응답하고, MySQL 저장은 이 publisher가 남긴
 * Stream 이벤트를 worker가 소비하면서 처리한다. 이 분리 덕분에 좌석 선점 hot path가 DB insert 지연에 묶이지 않는다.</p>
 */
@Component
public class ReservationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ReservationPersistenceProperties properties;

    public ReservationEventPublisher(StringRedisTemplate redisTemplate,
                                     ReservationPersistenceProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 좌석 선점 성공 이벤트를 Redis Stream에 추가한다.
     * 발행 실패를 API 예외로 전파하지 않는 이유는 좌석 선점의 실시간 정합성은 이미 Redis에서 결정됐고,
     * 이 경계는 MySQL 최종 저장을 위한 후속 처리 큐이기 때문이다.
     *
     * @param event Redis Stream에 append할 예약 성공 이벤트
     */
    public void publish(ReservationEvent event) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("reservationId", event.reservationId());
            body.put("eventId", event.eventId());
            body.put("userId", event.userId());
            body.put("seatId", event.seatId());
            body.put("status", event.status());
            body.put("reservedAt", event.reservedAt().toString());
            body.put("idempotencyKey", event.idempotencyKey());
            // Redis Stream은 성공한 좌석 선점 결과를 MySQL worker에게 넘기는 비동기 경계다.
            // 이 경계 덕분에 사용자는 DB insert 완료를 기다리지 않는다.
            redisTemplate.opsForStream().add(properties.streamKey(), body);
        } catch (Exception e) {
            log.warn("Failed to publish reservation event for reservationId={}: {}",
                    event.reservationId(), e.getMessage());
        }
    }
}
