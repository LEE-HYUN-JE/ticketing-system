package com.example.ticketing.queue.domain;

import java.time.Instant;

public final class QueueModels {

    private QueueModels() {
    }

    public record QueueEntry(String eventId, String userId, Instant requestedAt, String queueToken) {
    }

    public record QueueTokenMapping(String token, String eventId, String userId, Instant createdAt) {
    }

    public record ActiveAdmission(String eventId, String userId, Instant enteredAt, Instant expiresAt) {
    }

    public record QueuePosition(long rank, long totalWaiting) {
    }
}

