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
