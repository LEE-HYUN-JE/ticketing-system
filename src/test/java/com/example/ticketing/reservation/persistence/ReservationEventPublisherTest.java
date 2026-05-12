package com.example.ticketing.reservation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.ticketing.reservation.domain.ReservationEvent;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;

class ReservationEventPublisherTest extends RedisIntegrationTestSupport {

    @Autowired
    private ReservationEventPublisher publisher;

    @Autowired
    private ReservationPersistenceProperties properties;

    @Test
    void publishAddsMessageToStream() {
        ReservationEvent event = new ReservationEvent(
                "res-001", "event-1", "user-1", "seat-1",
                "RESERVED", Instant.parse("2026-05-10T06:00:00Z"), "idem-key-1"
        );

        publisher.publish(event);

        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                .read(StreamOffset.create(properties.streamKey(), ReadOffset.from("0")));
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getValue()).containsEntry("reservationId", "res-001")
                .containsEntry("eventId", "event-1")
                .containsEntry("userId", "user-1")
                .containsEntry("seatId", "seat-1")
                .containsEntry("status", "RESERVED")
                .containsEntry("idempotencyKey", "idem-key-1");
    }

    @Test
    void publishDoesNotThrowOnRedisFailure() {
        // RedisTemplate 내부 오류를 직접 시뮬레이션하기 어려우므로,
        // 잘못된 스트림 키에 대해서도 예외가 전파되지 않는지 확인
        ReservationEventPublisher faultPublisher = new ReservationEventPublisher(
                redisTemplate,
                new ReservationPersistenceProperties("", "g", "c", 10, 30000, false)
        );
        ReservationEvent event = new ReservationEvent(
                "res-002", "event-1", "user-2", "seat-2",
                "RESERVED", Instant.now(), "idem-key-2"
        );

        assertThatCode(() -> faultPublisher.publish(event)).doesNotThrowAnyException();
    }
}
