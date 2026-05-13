package com.example.ticketing.queue.application;

import com.example.ticketing.queue.infrastructure.RedisAdmissionRepository;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 대기열 사용자를 active admission으로 전환하는 유스케이스를 담당한다.
 *
 * <p>이 서비스는 HTTP 요청을 직접 처리하지 않는다. 별도 scheduler 컨테이너가 주기적으로 호출하며,
 * Redis에 등록된 이벤트 목록을 기준으로 각 이벤트의 waiting queue에서 오래 기다린 사용자부터 정해진 수만큼 입장시킨다.</p>
 */
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
     *
     * @param eventId 입장 허가를 진행할 티켓팅 이벤트 식별자
     * @return 이번 tick에서 active 상태로 전환된 사용자 ID 목록
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
     *
     * @return 전체 이벤트에서 이번 tick 동안 active 상태로 전환된 사용자 수
     */
    public long admitAllRegisteredEvents() {
        return queueRepository.findQueueEvents()
                .stream()
                .mapToLong(eventId -> admitOneTick(eventId).size())
                .sum();
    }
}
