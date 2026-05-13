package com.example.ticketing.queue.application;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 대기열 운영 상태를 Redis 기준으로 집계하는 조회 서비스다.
 *
 * <p>메트릭은 비즈니스 트랜잭션을 만들지 않고 Redis counter와 ZSET cardinality만 읽는다.
 * active 사용자 수는 추적용 ZSET에서 만료 항목을 먼저 제거한 뒤 계산해, TTL key를 직접 SCAN하지 않는다.</p>
 */
@Service
public class QueueMetricsService {

    private final RedisQueueRepository queueRepository;
    private final QueueRedisKeys keys;
    private final Clock clock;

    @Autowired
    public QueueMetricsService(RedisQueueRepository queueRepository, QueueRedisKeys keys) {
        this(queueRepository, keys, Clock.systemUTC());
    }

    QueueMetricsService(RedisQueueRepository queueRepository, QueueRedisKeys keys, Clock clock) {
        this.queueRepository = queueRepository;
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * 전체 이벤트의 누적 등록/입장 수와 현재 waiting/active 사용자 수를 계산한다.
     *
     * @return Redis에 기록된 대기열 메트릭 스냅샷
     */
    public QueueMetricsSnapshot snapshot() {
        long nowEpochMillis = Instant.now(clock).toEpochMilli();
        long currentWaiting = 0L;
        long currentActive = 0L;
        for (String eventId : queueRepository.findQueueEvents()) {
            currentWaiting += queueRepository.countWaiting(eventId);
            queueRepository.removeExpiredActiveUsers(eventId, nowEpochMillis);
            currentActive += queueRepository.countTrackedActiveUsers(eventId);
        }

        return new QueueMetricsSnapshot(
                queueRepository.metricValue(keys.metricsRegistered()),
                queueRepository.metricValue(keys.metricsAdmitted()),
                currentWaiting,
                currentActive,
                queueRepository.metricValue(keys.metricsExpiredLookup())
        );
    }

    /**
     * 운영자나 테스트 코드가 대기열 상태를 한 번에 확인할 수 있도록 묶은 읽기 전용 메트릭 값이다.
     *
     * @param registeredCount 대기열 등록 누적 수
     * @param admittedCount active 전환 누적 수
     * @param currentWaitingCount 현재 waiting ZSET에 남아 있는 사용자 수
     * @param currentActiveCount 현재 active 추적 ZSET에 남아 있는 사용자 수
     * @param expiredLookupCount 만료된 token/active 상태를 조회한 누적 수
     */
    public record QueueMetricsSnapshot(
            long registeredCount,
            long admittedCount,
            long currentWaitingCount,
            long currentActiveCount,
            long expiredLookupCount
    ) {
    }
}
