package com.example.ticketing.queue.domain;

import java.time.Instant;

public record ActiveAdmission(String eventId, String userId, Instant enteredAt, Instant expiresAt) {
}
