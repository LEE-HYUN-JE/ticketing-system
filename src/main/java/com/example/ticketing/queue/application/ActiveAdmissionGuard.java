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

    /**
     * Reservation 계층으로 넘어가기 전 사용자가 active admission을 보유했는지 강제한다.
     * active token이 없으면 좌석 선점 hot path로 진입시키지 않아 예매 API의 유입량을 제한한다.
     */
    public void requireActiveAdmission(String eventId, String userId) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);
        if (!hasActiveAdmission(eventId, userId)) {
            queueRepository.incrementExpiredLookup();
            throw new IllegalStateException("Active admission is required");
        }
    }

    /**
     * Redis active TTL key를 기준으로 예매 가능 상태인지 확인한다.
     * TTL이 만료된 사용자는 대기열에서 입장했더라도 다시 좌석 선점을 시도할 수 없다.
     */
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
