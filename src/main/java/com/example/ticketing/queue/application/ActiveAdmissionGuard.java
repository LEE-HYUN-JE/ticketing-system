package com.example.ticketing.queue.application;

import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Reservation 계층으로 넘어가는 사용자가 active admission을 보유했는지 확인하는 경계 컴포넌트다.
 *
 * <p>현재 좌석 선점 자체도 Redis Lua script에서 active key를 다시 확인하지만, 이 guard는
 * 애플리케이션 계층에서 "예매 권한이 없는 요청은 hot path로 보내지 않는다"는 의도를 드러내는 역할을 한다.</p>
 */
@Component
public class ActiveAdmissionGuard {

    private final RedisQueueRepository queueRepository;

    public ActiveAdmissionGuard(RedisQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    /**
     * Reservation 계층으로 넘어가기 전 사용자가 active admission을 보유했는지 강제한다.
     * active token이 없으면 좌석 선점 hot path로 진입시키지 않아 예매 API의 유입량을 제한한다.
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @throws IllegalArgumentException 필수 식별자가 비어 있는 경우
     * @throws IllegalStateException active admission이 없거나 만료된 경우
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
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @return active TTL key가 아직 살아 있으면 {@code true}
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
