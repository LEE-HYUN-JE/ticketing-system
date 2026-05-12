package com.example.ticketing.reservation.persistence;

import com.example.ticketing.reservation.domain.ReservationModels.ReservationEvent;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
