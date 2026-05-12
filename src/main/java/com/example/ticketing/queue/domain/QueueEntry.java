package com.example.ticketing.queue.domain;

import java.time.Instant;

public record QueueEntry(String eventId, String userId, Instant requestedAt, String queueToken) {
}
