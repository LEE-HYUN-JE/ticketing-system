package com.example.ticketing.queue.infrastructure;

import org.springframework.stereotype.Component;

/**
 * Queue 도메인에서 사용하는 Redis key 이름을 한 곳에서 생성한다.
 *
 * <p>Redis key는 대기열 정합성의 계약이므로 문자열을 각 repository에 흩뿌리지 않는다.
 * 이 클래스는 waiting queue, queue token, active admission, 운영 메트릭 key의 naming convention을
 * 코드에서 명시적으로 드러내는 역할을 한다.</p>
 */
@Component
public class QueueRedisKeys {

    /**
     * 이벤트별 waiting queue Sorted Set key.
     * member는 userId, score는 대기열 진입 시각 기반 정렬값이다.
     */
    public String waiting(String eventId) {
        return "waiting:%s".formatted(eventId);
    }

    /**
     * queue token으로 event/user 정보를 복원하기 위한 Hash key.
     */
    public String queueToken(String token) {
        return "queue-token:%s".formatted(token);
    }

    /**
     * 동일 event/user의 중복 대기열 진입을 기존 token으로 수렴시키기 위한 역색인 String key.
     */
    public String queueUserToken(String eventId, String userId) {
        return "queue-user-token:%s:%s".formatted(eventId, userId);
    }

    /**
     * 스케줄러가 Redis KEYS 스캔 없이 처리 대상 eventId를 찾기 위한 Set key.
     */
    public String queueEvents() {
        return "queue-events";
    }

    /**
     * 사용자가 예매 API를 호출할 수 있는 active admission TTL key.
     */
    public String active(String eventId, String userId) {
        return "active:%s:%s".formatted(eventId, userId);
    }

    /**
     * Lua script가 active key를 사용자별로 생성할 때 사용하는 event 단위 prefix.
     */
    public String activePrefix(String eventId) {
        return "active:%s:".formatted(eventId);
    }

    /**
     * active 사용자 수를 SCAN 없이 관찰하기 위한 추적용 Sorted Set key.
     */
    public String activeUsers(String eventId) {
        return "active-users:%s".formatted(eventId);
    }

    public String metricsRegistered() {
        return "queue-metrics:registered";
    }

    public String metricsAdmitted() {
        return "queue-metrics:admitted";
    }

    public String metricsExpiredLookup() {
        return "queue-metrics:expired-lookup";
    }
}
