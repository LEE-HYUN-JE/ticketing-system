package com.example.ticketing.queue.application;

import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ActiveAdmissionGuard {

    private final RedisQueueRepository queueRepository;

    public ActiveAdmissionGuard(RedisQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    public void requireActiveAdmission(String eventId, String userId) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);
        if (!hasActiveAdmission(eventId, userId)) {
            queueRepository.incrementExpiredLookup();
            throw new IllegalStateException("Active admission is required");
        }
    }

    public boolean hasActiveAdmission(String eventId, String userId) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);
        return queueRepository.getActiveTtlSeconds(eventId, userId) >= 0;
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}

