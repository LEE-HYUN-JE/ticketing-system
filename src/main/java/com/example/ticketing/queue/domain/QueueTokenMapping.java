package com.example.ticketing.queue.domain;

import java.time.Instant;

public record QueueTokenMapping(String token, String eventId, String userId, Instant createdAt) {
}
