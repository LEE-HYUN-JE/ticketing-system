package com.example.ticketing.queue.application;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public record QueueMetricsSnapshot(
            long registeredCount,
            long admittedCount,
            long currentWaitingCount,
            long currentActiveCount,
            long expiredLookupCount
    ) {
    }
}
