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

    public long admitAllRegisteredEvents() {
        return queueRepository.findQueueEvents()
                .stream()
                .mapToLong(eventId -> admitOneTick(eventId).size())
                .sum();
    }
}
