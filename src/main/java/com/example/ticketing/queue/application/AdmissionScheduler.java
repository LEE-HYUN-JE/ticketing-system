package com.example.ticketing.queue.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdmissionScheduler {

    private final AdmissionSchedulerService schedulerService;
    private final QueueProperties properties;

    public AdmissionScheduler(AdmissionSchedulerService schedulerService, QueueProperties properties) {
        this.schedulerService = schedulerService;
        this.properties = properties;
    }

    @Scheduled(fixedRate = 1000)
    public void admitWaitingUsers() {
        if (properties.schedulerEnabled()) {
            schedulerService.admitAllRegisteredEvents();
        }
    }
}

