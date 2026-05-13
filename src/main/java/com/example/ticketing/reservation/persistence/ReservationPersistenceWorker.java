package com.example.ticketing.reservation.persistence;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis Stream의 예약 성공 이벤트를 MySQL에 비동기로 저장하는 worker다.
 *
 * <p>이 worker는 API WAS와 분리된 컨테이너에서 실행되며, 좌석 선점 hot path와 MySQL insert를 분리한다.
 * Stream은 at-least-once 성격으로 동작하므로, 중복 저장은 MySQL unique constraint가 흡수하고,
 * 일시적 DB 오류는 XACK를 생략해 pending 재처리로 복구한다.</p>
 */
@Component
public class ReservationPersistenceWorker {

    private static final Logger log = LoggerFactory.getLogger(ReservationPersistenceWorker.class);

    private final StringRedisTemplate redisTemplate;
    private final ReservationJpaRepository jpaRepository;
    private final ReservationPersistenceProperties properties;

    public ReservationPersistenceWorker(
            StringRedisTemplate redisTemplate,
            ReservationJpaRepository jpaRepository,
            ReservationPersistenceProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.jpaRepository = jpaRepository;
        this.properties = properties;
    }

    /**
     * worker가 활성화된 인스턴스에서만 Redis Stream consumer group을 준비한다.
     * API WAS에서는 worker가 비활성화되어 있으므로 초기화 비용과 부작용이 없다.
     */
    @PostConstruct
    public void initConsumerGroup() {
        if (!properties.workerEnabled()) {
            return;
        }
        ensureConsumerGroup();
    }

    /**
     * Redis Stream consumer group은 메시지 소비 전 한 번 생성되어야 한다.
     * 운영에서는 worker 활성화 시점에, 테스트에서는 스케줄러를 끈 상태에서 명시적으로 호출한다.
     */
    public void ensureConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.xGroupCreate(
                        bytes(properties.streamKey()),
                        properties.consumerGroup(),
                        ReadOffset.from("0"),
                        true
                );
                return null;
            });
        } catch (Exception e) {
            // 이미 존재하면 무시
        }
    }

    /**
     * worker가 활성화된 인스턴스에서 Redis Stream을 주기적으로 소비한다.
     * 먼저 오래된 pending 메시지를 회수하고, 그 다음 새 메시지를 읽어 장애 후 재처리와 정상 소비를 함께 진행한다.
     */
    @Scheduled(fixedDelay = 100)
    public void scheduledProcess() {
        if (!properties.workerEnabled()) {
            return;
        }
        reclaimAndProcess(properties.pendingIdleMs());
        processOnce();
    }

    /**
     * 이 consumer group의 pending 메시지 중 idle 시간이 지난 메시지를 다시 소유해 처리한다.
     *
     * @param idleMs 메시지가 pending 상태로 머문 시간이 이 값 이상이면 재처리 대상으로 본다
     */
    public void reclaimAndProcess(long idleMs) {
        try {
            List<ByteRecord> claimed = claimPendingMessages(Math.max(1, idleMs));
            if (!claimed.isEmpty()) {
                processClaimedMessages(claimed);
            }
        } catch (Exception e) {
            log.warn("Pending reprocess error: {}", e.getMessage());
        }
    }

    /**
     * consumer group에 아직 전달되지 않은 새 예약 이벤트를 한 batch 읽어 MySQL에 저장한다.
     * 정상 처리된 메시지는 XACK하고, DB unique constraint 중복은 이미 처리된 이벤트로 보고 skip한다.
     */
    public void processOnce() {
        try {
            // lastConsumed(">")는 이 consumer group에 아직 전달되지 않은 새 메시지만 읽는다.
            // 장애로 pending에 남은 메시지는 scheduledProcess의 reclaim 단계에서 별도로 회수한다.
            List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                    Consumer.from(properties.consumerGroup(), properties.consumerName()),
                    StreamReadOptions.empty().count(properties.batchSize()).block(Duration.ofMillis(200)),
                    StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed())
            );
            if (messages != null && !messages.isEmpty()) {
                processReadGroupMessages(messages);
            }
        } catch (Exception e) {
            log.warn("XREADGROUP error: {}", e.getMessage());
        }
    }

    private List<ByteRecord> claimPendingMessages(long idleMs) {
        return redisTemplate.execute((RedisCallback<List<ByteRecord>>) connection -> {
            PendingMessages pendingMessages = connection.xPending(
                    bytes(properties.streamKey()),
                    Consumer.from(properties.consumerGroup(), properties.consumerName())
            );
            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return List.of();
            }

            List<RecordId> ids = new ArrayList<>();
            for (PendingMessage pendingMessage : pendingMessages) {
                if (pendingMessage.getElapsedTimeSinceLastDelivery().toMillis() >= idleMs) {
                    ids.add(pendingMessage.getId());
                }
            }
            if (ids.isEmpty()) {
                return List.of();
            }

            return connection.xClaim(
                    bytes(properties.streamKey()),
                    properties.consumerGroup(),
                    properties.consumerName(),
                    Duration.ofMillis(idleMs),
                    ids.toArray(new RecordId[0])
            );
        });
    }

    private void processReadGroupMessages(List<MapRecord<String, Object, Object>> messages) {
        for (MapRecord<String, Object, Object> message : messages) {
            processMessage(
                    message.getId().toString(),
                    stringBody(message.getValue().get("reservationId")),
                    stringBody(message.getValue().get("eventId")),
                    stringBody(message.getValue().get("userId")),
                    stringBody(message.getValue().get("seatId")),
                    stringBody(message.getValue().get("status")),
                    stringBody(message.getValue().get("reservedAt")),
                    stringBody(message.getValue().get("idempotencyKey"))
            );
        }
    }

    private void processClaimedMessages(List<ByteRecord> messages) {
        for (ByteRecord message : messages) {
            Map<byte[], byte[]> body = message.getValue();
            processMessage(
                    message.getId().toString(),
                    stringBody(valueFor(body, "reservationId")),
                    stringBody(valueFor(body, "eventId")),
                    stringBody(valueFor(body, "userId")),
                    stringBody(valueFor(body, "seatId")),
                    stringBody(valueFor(body, "status")),
                    stringBody(valueFor(body, "reservedAt")),
                    stringBody(valueFor(body, "idempotencyKey"))
            );
        }
    }

    private void processMessage(
            String messageId,
            String reservationId,
            String eventId,
            String userId,
            String seatId,
            String status,
            String reservedAt,
            String idempotencyKey
    ) {
        try {
            ReservationEntity entity = new ReservationEntity(
                    reservationId,
                    eventId,
                    userId,
                    seatId,
                    status,
                    Instant.parse(reservedAt),
                    idempotencyKey,
                    Instant.now()
            );
            jpaRepository.save(entity);
            log.debug("Saved reservation={} event={} user={} seat={}",
                    entity.getId(), entity.getEventId(), entity.getUserId(), entity.getSeatId());
            ack(messageId);
        } catch (DataIntegrityViolationException e) {
            // Redis Stream은 at-least-once에 가깝게 동작한다. 재전달 중복은 MySQL unique constraint가 최종 방어선이다.
            log.warn("Duplicate reservation skipped: messageId={}", messageId);
            ack(messageId);
        } catch (Exception e) {
            // DB 타임아웃·연결 실패 같은 일시적 오류는 ACK 하지 않는다.
            // 메시지가 펜딩 목록에 남아 reclaimAndProcess 가 pendingIdleMs 이후 재처리한다.
            log.error("Failed to process message id={}, pending reclaim will retry: {}", messageId, e.getMessage());
        }
    }

    private String stringBody(Object value) {
        return value == null ? null : value.toString();
    }

    private String stringBody(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private byte[] valueFor(Map<byte[], byte[]> body, String field) {
        byte[] target = bytes(field);
        for (Map.Entry<byte[], byte[]> entry : body.entrySet()) {
            if (java.util.Arrays.equals(entry.getKey(), target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void ack(String messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(
                    properties.streamKey(),
                    properties.consumerGroup(),
                    messageId
            );
        } catch (Exception e) {
            log.warn("XACK failed for messageId={}: {}", messageId, e.getMessage());
        }
    }
}
