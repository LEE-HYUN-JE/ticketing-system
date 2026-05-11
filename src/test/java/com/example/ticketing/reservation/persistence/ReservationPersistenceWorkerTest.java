package com.example.ticketing.reservation.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

class ReservationPersistenceWorkerTest extends RedisIntegrationTestSupport {

    @Autowired
    private ReservationPersistenceWorker worker;

    @Autowired
    private ReservationJpaRepository jpaRepository;

    @Autowired
    private ReservationPersistenceProperties properties;

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
        worker.initConsumerGroup();
    }

    @Test
    void workerConsumesStreamAndSavesToDb() throws InterruptedException {
        String reservationId = UUID.randomUUID().toString();
        Map<String, String> body = new HashMap<>();
        body.put("reservationId", reservationId);
        body.put("eventId", "event-1");
        body.put("userId", "user-1");
        body.put("seatId", "seat-1");
        body.put("status", "RESERVED");
        body.put("reservedAt", "2026-05-10T06:00:00Z");
        body.put("idempotencyKey", "idem-1");
        redisTemplate.opsForStream().add(properties.streamKey(), body);

        worker.processOnce();

        List<ReservationEntity> saved = jpaRepository.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getId()).isEqualTo(reservationId);
        assertThat(saved.get(0).getEventId()).isEqualTo("event-1");
        assertThat(saved.get(0).getSeatId()).isEqualTo("seat-1");
    }

    @Test
    void duplicateEventIsAckedWithoutException() {
        String reservationId = UUID.randomUUID().toString();
        Map<String, String> body = new HashMap<>();
        body.put("reservationId", reservationId);
        body.put("eventId", "event-1");
        body.put("userId", "user-2");
        body.put("seatId", "seat-2");
        body.put("status", "RESERVED");
        body.put("reservedAt", "2026-05-10T06:00:00Z");
        body.put("idempotencyKey", "idem-2");
        redisTemplate.opsForStream().add(properties.streamKey(), body);
        redisTemplate.opsForStream().add(properties.streamKey(), body);

        worker.processOnce();
        worker.processOnce();

        assertThat(jpaRepository.findAll()).hasSize(1);
    }

    @Test
    void pendingMessagesAreReprocessedOnRestart() {
        String reservationId = UUID.randomUUID().toString();
        Map<String, String> body = new HashMap<>();
        body.put("reservationId", reservationId);
        body.put("eventId", "event-1");
        body.put("userId", "user-3");
        body.put("seatId", "seat-3");
        body.put("status", "RESERVED");
        body.put("reservedAt", "2026-05-10T06:00:00Z");
        body.put("idempotencyKey", "idem-3");
        redisTemplate.opsForStream().add(properties.streamKey(), body);

        // worker-1이 메시지를 읽어 pending 상태로 만들고 ACK하지 않는 상황 시뮬레이션
        redisTemplate.opsForStream().read(
                Consumer.from(properties.consumerGroup(), properties.consumerName()),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed())
        );

        // 재시작 시 pending 메시지를 재처리 (offset "0"으로 읽기)
        worker.reclaimAndProcess(0);

        assertThat(jpaRepository.findAll()).hasSize(1);
    }
}
