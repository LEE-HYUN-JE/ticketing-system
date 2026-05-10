package com.example.ticketing.queue.infrastructure;

import org.springframework.stereotype.Component;

@Component
public class QueueRedisKeys {

    public String waiting(String eventId) {
        return "waiting:%s".formatted(eventId);
    }

    public String queueToken(String token) {
        return "queue-token:%s".formatted(token);
    }

    public String queueUserToken(String eventId, String userId) {
        return "queue-user-token:%s:%s".formatted(eventId, userId);
    }

    public String queueEvents() {
        return "queue-events";
    }

    public String active(String eventId, String userId) {
        return "active:%s:%s".formatted(eventId, userId);
    }

    public String activePrefix(String eventId) {
        return "active:%s:".formatted(eventId);
    }

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
