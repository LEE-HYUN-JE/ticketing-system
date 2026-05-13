package com.example.ticketing.queue.application;

import com.example.ticketing.queue.api.dto.QueueStatusResponse;
import com.example.ticketing.queue.domain.QueuePosition;
import com.example.ticketing.queue.domain.QueueTokenMapping;
import com.example.ticketing.queue.domain.QueueStatus;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * queue token 기반으로 사용자의 현재 대기열 상태를 계산한다.
 *
 * <p>서버 인스턴스는 사용자별 세션을 보관하지 않는다. token을 Redis에서 event/user로 복원한 뒤,
 * waiting ZSET과 active TTL key를 순서대로 확인해 {@code WAITING}, {@code ENTERED}, {@code EXPIRED}를 판단한다.</p>
 */
@Service
public class QueueStatusService {

    private final RedisQueueRepository queueRepository;
    private final QueueProperties properties;

    public QueueStatusService(RedisQueueRepository queueRepository, QueueProperties properties) {
        this.queueRepository = queueRepository;
        this.properties = properties;
    }

    /**
     * queue token을 event/user로 해석한 뒤 현재 대기 상태를 계산한다.
     * waiting ZSET에 있으면 순번을, waiting에서 빠졌고 active TTL이 남아 있으면 ENTERED를, 둘 다 아니면 EXPIRED를 반환한다.
     *
     * @param eventId 조회 대상 이벤트 식별자
     * @param queueToken 대기열 진입 시 발급받은 polling token
     * @return 사용자의 현재 대기열 상태 응답
     * @throws IllegalArgumentException token 형식이 잘못됐거나 다른 이벤트에 속한 token인 경우
     */
    public QueueStatusResponse getStatus(String eventId, String queueToken) {
        validateRequired("eventId", eventId);
        validateQueueToken(queueToken);

        Optional<QueueTokenMapping> tokenMapping = queueRepository.findTokenMapping(queueToken);
        if (tokenMapping.isEmpty()) {
            queueRepository.incrementExpiredLookup();
            return expired();
        }

        QueueTokenMapping mapping = tokenMapping.get();
        if (!mapping.eventId().equals(eventId)) {
            throw new IllegalArgumentException("queueToken does not belong to eventId");
        }

        return queueRepository.getWaitingPosition(mapping.eventId(), mapping.userId())
                .map(this::waiting)
                .orElseGet(() -> activeOrExpired(mapping));
    }

    private QueueStatusResponse activeOrExpired(QueueTokenMapping mapping) {
        long activeTtlSeconds = queueRepository.getActiveTtlSeconds(mapping.eventId(), mapping.userId());
        if (activeTtlSeconds >= 0) {
            return new QueueStatusResponse(QueueStatus.ENTERED, null, null, null, activeTtlSeconds);
        }
        queueRepository.incrementExpiredLookup();
        return expired();
    }

    private QueueStatusResponse waiting(QueuePosition position) {
        return new QueueStatusResponse(
                QueueStatus.WAITING,
                position.rank(),
                position.totalWaiting(),
                properties.pollAfterSeconds(),
                null
        );
    }

    private static QueueStatusResponse expired() {
        return new QueueStatusResponse(QueueStatus.EXPIRED, null, null, null, null);
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void validateQueueToken(String queueToken) {
        validateRequired("queueToken", queueToken);
        try {
            UUID.fromString(queueToken);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("queueToken must be a valid UUID");
        }
    }
}
