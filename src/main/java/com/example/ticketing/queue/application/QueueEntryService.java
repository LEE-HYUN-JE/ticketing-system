package com.example.ticketing.queue.application;

import com.example.ticketing.queue.api.dto.QueueEntryResponse;
import com.example.ticketing.queue.domain.QueueStatus;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QueueEntryService {

    private final RedisQueueRepository queueRepository;
    private final QueueProperties properties;
    private final AtomicLong scoreSequence = new AtomicLong();

    public QueueEntryService(RedisQueueRepository queueRepository, QueueProperties properties) {
        this.queueRepository = queueRepository;
        this.properties = properties;
    }

    /**
     * 사용자를 Redis 대기열에 등록하고 polling에 사용할 queue token을 반환한다.
     * 동일 event/user가 반복 진입하면 Lua script가 기존 token으로 수렴시키므로 사용자는 하나의 대기 위치만 가진다.
     */
    public QueueEntryResponse enter(String eventId, String userId) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);

        String token = registerToken(eventId, userId);
        queueRepository.incrementRegistered();

        return new QueueEntryResponse(
                token,
                QueueStatus.WAITING,
                null,
                null,
                properties.pollAfterSeconds()
        );
    }

    private String registerToken(String eventId, String userId) {
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();
        return queueRepository.registerQueueEntry(
                token,
                eventId,
                userId,
                now,
                Duration.ofSeconds(properties.tokenTtlSeconds()),
                waitingScore(now)
        );
    }

    /**
     * waiting ZSET score는 요청 시각에 작은 sequence를 더해 만든다.
     * 같은 millisecond에 많은 요청이 들어와도 score 충돌을 줄여 오래 기다린 사용자 순서를 안정적으로 유지한다.
     */
    private double waitingScore(Instant requestedAt) {
        long sequence = scoreSequence.getAndIncrement() % 1_000_000L;
        return requestedAt.toEpochMilli() + (sequence / 1_000_000.0d);
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
