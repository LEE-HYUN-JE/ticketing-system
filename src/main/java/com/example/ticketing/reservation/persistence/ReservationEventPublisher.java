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
            redisTemplate.opsForStream().add(properties.streamKey(), body);
        } catch (Exception e) {
            log.warn("Failed to publish reservation event for reservationId={}: {}",
                    event.reservationId(), e.getMessage());
        }
    }
}
