package com.example.ticketing.queue.application;

import com.example.ticketing.queue.infrastructure.RedisAdmissionRepository;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdmissionSchedulerService {

    private final RedisAdmissionRepository admissionRepository;
    private final RedisQueueRepository queueRepository;
    private final QueueProperties properties;
    private final Clock clock;

    @Autowired
    public AdmissionSchedulerService(
            RedisAdmissionRepository admissionRepository,
            RedisQueueRepository queueRepository,
            QueueProperties properties
    ) {
        this(admissionRepository, queueRepository, properties, Clock.systemUTC());
    }

    AdmissionSchedulerService(
            RedisAdmissionRepository admissionRepository,
            RedisQueueRepository queueRepository,
            QueueProperties properties,
            Clock clock
    ) {
        this.admissionRepository = admissionRepository;
        this.queueRepository = queueRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 특정 event의 waiting 사용자 중 설정된 admission rate만큼 active 상태로 전환한다.
     * 한 tick은 트래픽 밸브 역할을 하며, Reservation API로 흘러가는 사용자 수를 초 단위로 제한한다.
     */
    public List<String> admitOneTick(String eventId) {
        List<String> admitted = admissionRepository.admitOldest(
                eventId,
                properties.admissionRatePerSecond(),
                properties.activeTtlSeconds(),
                Instant.now(clock)
        );
        queueRepository.incrementAdmitted(admitted.size());
        return admitted;
    }

    /**
     * Redis에 등록된 모든 event 대기열을 순회하며 admission tick을 수행한다.
     * Redis KEYS 스캔 대신 queue-events registry를 사용해 로컬 부하 테스트에서도 예측 가능한 접근 패턴을 유지한다.
     */
    public long admitAllRegisteredEvents() {
        return queueRepository.findQueueEvents()
                .stream()
                .mapToLong(eventId -> admitOneTick(eventId).size())
                .sum();
    }
}
